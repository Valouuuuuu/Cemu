#include "Cafe/HW/Latte/Core/Latte.h"
#include "Cafe/HW/Latte/Core/LatteShader.h"
#include "Cafe/HW/Latte/Core/LatteOverlay.h"
#include "Cafe/HW/Latte/Core/LatteCostProbe.h"

#include <algorithm>
#include <chrono>
#include <map>
#include <mutex>
#include <numeric>
#include <optional>

std::atomic_bool g_latteCostProbeRunning = false;

namespace
{
	constexpr uint32 kStartDelayFrames = 300; // time to get back into the game
	constexpr uint32 kCollectFrames = 180;
	constexpr uint32 kStepFrames = 60;
	constexpr uint32 kSettleFrames = 10; // frames after a change that are not timed
	constexpr double kHitchMs = 200.0; // longer frames are loading or pauses, not drawing
	constexpr size_t kMostDrawn = 36;
	constexpr size_t kMostVertices = 12;
	constexpr size_t kReferenceEvery = 4;
	constexpr size_t kReportLines = 14;

	using ShaderKey = std::pair<uint64, uint64>;

	struct ShaderStats
	{
		uint64 draws{};
		uint64 vertices{};
	};

	struct Step
	{
		std::optional<ShaderKey> hidden; // nothing hidden: the reference
		double sumMs{};
		uint32 timedFrames{};

		double AverageMs() const
		{
			return timedFrames ? sumMs / timedFrames : 0.0;
		}
	};

	enum class Phase
	{
		IDLE,
		WAITING,
		COLLECTING,
		TESTING,
	};

	std::atomic_bool s_requested = false;
	std::mutex s_reportMutex;
	std::string s_report = "Pas encore d'analyse.";

	Phase s_phase = Phase::IDLE;
	uint32 s_phaseFrame = 0;
	std::chrono::steady_clock::time_point s_lastFrame;
	std::map<ShaderKey, ShaderStats> s_stats;
	std::vector<Step> s_steps;
	size_t s_step = 0;
	std::optional<ShaderKey> s_hidden;

	ShaderKey KeyOf(const LatteDecompilerShader* pixelShader)
	{
		// draws without a pixel shader only write depth, such as the shadows
		if (!pixelShader)
			return {0, 0};
		return {pixelShader->baseHash, pixelShader->auxHash};
	}

	std::string Describe(const ShaderKey& key)
	{
		if (key == ShaderKey{0, 0})
			return "sans couleur (ombres, profondeur)";
		// the names that graphic packs give to the shaders they replace
		return fmt::format("{:016x}_{:016x}", key.first, key.second);
	}

	void SetReport(std::string text)
	{
		std::lock_guard lock(s_reportMutex);
		s_report = std::move(text);
	}

	void StartRun()
	{
		s_phase = Phase::WAITING;
		s_phaseFrame = 0;
		s_lastFrame = std::chrono::steady_clock::now();
		s_stats.clear();
		s_steps.clear();
		s_hidden.reset();
		g_latteCostProbeRunning = true;
		SetReport("Analyse en cours : elle commence dans quelques secondes. Reste dans le jeu.");
		LatteOverlay_pushNotification("Analyse des FPS dans quelques secondes : reste dans le jeu, sans bouger. L'image va clignoter pendant environ 3 minutes.", 8000);
	}

	void Finish()
	{
		s_hidden.reset();
		s_phase = Phase::IDLE;
		g_latteCostProbeRunning = false;

		std::vector<double> references;
		for (const Step& step : s_steps)
		{
			if (!step.hidden && step.timedFrames > 0)
				references.push_back(step.AverageMs());
		}
		if (references.empty())
		{
			SetReport("L'analyse n'a pas pu mesurer d'images.");
			return;
		}
		const double referenceMs = std::accumulate(references.begin(), references.end(), 0.0) / references.size();
		const auto [shortest, longest] = std::minmax_element(references.begin(), references.end());

		struct Result
		{
			ShaderKey key;
			double gainMs;
			ShaderStats stats;
		};
		std::vector<Result> results;
		for (size_t i = 0; i < s_steps.size(); i++)
		{
			const Step& step = s_steps[i];
			if (!step.hidden || step.timedFrames == 0)
				continue;
			// the references timed just before and just after this effect was hidden
			double nearbyMs = 0.0;
			uint32 nearbyCount = 0;
			for (size_t before = i; before-- > 0;)
			{
				if (!s_steps[before].hidden && s_steps[before].timedFrames > 0)
				{
					nearbyMs += s_steps[before].AverageMs();
					nearbyCount++;
					break;
				}
			}
			for (size_t after = i + 1; after < s_steps.size(); after++)
			{
				if (!s_steps[after].hidden && s_steps[after].timedFrames > 0)
				{
					nearbyMs += s_steps[after].AverageMs();
					nearbyCount++;
					break;
				}
			}
			const double nearbyAverageMs = nearbyCount ? nearbyMs / nearbyCount : referenceMs;
			ShaderStats stats;
			if (auto found = s_stats.find(*step.hidden); found != s_stats.end())
				stats = found->second;
			results.push_back({*step.hidden, nearbyAverageMs - step.AverageMs(), stats});
		}
		std::sort(results.begin(), results.end(), [](const Result& a, const Result& b) { return a.gainMs > b.gainMs; });

		std::string report = fmt::format("Image normale : {:.1f} ms ({:.1f} images/s), entre {:.1f} et {:.1f} ms. {} effets testés.\n",
			referenceMs, 1000.0 / referenceMs, *shortest, *longest, results.size());
		report += "Effet caché : temps gagné par image (images/s obtenues) :\n";
		for (size_t i = 0; i < results.size() && i < kReportLines; i++)
		{
			const Result& result = results[i];
			const double hiddenMs = referenceMs - result.gainMs;
			report += fmt::format("{:2}. {:+.1f} ms ({:.0f} images/s)  {}  {:.1f} dessins/image\n",
				i + 1, -result.gainMs, hiddenMs > 1.0 ? 1000.0 / hiddenMs : 0.0, Describe(result.key), (double)result.stats.draws / kCollectFrames);
		}
		SetReport(report);
		cemuLog_log(LogType::Force, "Cost analysis:\n{}", report);
		LatteOverlay_pushNotification("Analyse terminée : ouvre le portail (Select + R1), puis Analyse, pour voir le résultat.", 10000);
	}

	void PlanSteps()
	{
		// the pixel shaders drawn in at least a quarter of the frames: the most drawn, then those with the most vertices
		std::vector<std::pair<ShaderKey, ShaderStats>> seen;
		for (const auto& [key, stats] : s_stats)
		{
			if (stats.draws >= kCollectFrames / 4)
				seen.emplace_back(key, stats);
		}
		std::vector<ShaderKey> candidates;
		std::sort(seen.begin(), seen.end(), [](const auto& a, const auto& b) { return a.second.draws > b.second.draws; });
		for (const auto& [key, stats] : seen)
		{
			if (candidates.size() < kMostDrawn)
				candidates.push_back(key);
		}
		std::sort(seen.begin(), seen.end(), [](const auto& a, const auto& b) { return a.second.vertices > b.second.vertices; });
		size_t addedForVertices = 0;
		for (const auto& [key, stats] : seen)
		{
			if (addedForVertices < kMostVertices && std::find(candidates.begin(), candidates.end(), key) == candidates.end())
			{
				candidates.push_back(key);
				addedForVertices++;
			}
		}
		if (candidates.empty())
		{
			s_phase = Phase::IDLE;
			g_latteCostProbeRunning = false;
			SetReport("L'analyse n'a vu aucun dessin : la scène était-elle affichée ?");
			return;
		}

		s_steps.clear();
		s_steps.push_back({});
		for (size_t i = 0; i < candidates.size(); i++)
		{
			Step step;
			step.hidden = candidates[i];
			s_steps.push_back(step);
			if ((i + 1) % kReferenceEvery == 0 || i + 1 == candidates.size())
				s_steps.push_back({});
		}
		s_step = 0;
		s_phase = Phase::TESTING;
		s_phaseFrame = 0;
		s_hidden = s_steps[0].hidden;
		SetReport("Analyse en cours : 0 %");
		LatteOverlay_pushNotification("Analyse en cours : ne touche à rien, reste au même endroit.", 6000);
	}

	void TimeFrame(double frameMs)
	{
		Step& step = s_steps[s_step];
		if (s_phaseFrame > kSettleFrames && frameMs < kHitchMs)
		{
			step.sumMs += frameMs;
			step.timedFrames++;
		}
		if (s_phaseFrame < kStepFrames)
			return;
		s_step++;
		s_phaseFrame = 0;
		if (s_step >= s_steps.size())
		{
			Finish();
			return;
		}
		s_hidden = s_steps[s_step].hidden;
		SetReport(fmt::format("Analyse en cours : {} %", s_step * 100 / s_steps.size()));
	}
}

void LatteCostProbe_Request()
{
	// a run that is already going on is not restarted when it ends
	if (!LatteCostProbe_IsRunning())
		s_requested = true;
}

std::string LatteCostProbe_GetReport()
{
	std::lock_guard lock(s_reportMutex);
	return s_report;
}

void LatteCostProbe_CountDraw(const LatteDecompilerShader* pixelShader, uint32 vertexCount)
{
	if (s_phase != Phase::COLLECTING)
		return;
	ShaderStats& stats = s_stats[KeyOf(pixelShader)];
	stats.draws++;
	stats.vertices += vertexCount;
}

bool LatteCostProbe_ShouldHide(const LatteDecompilerShader* pixelShader)
{
	return s_hidden.has_value() && *s_hidden == KeyOf(pixelShader);
}

void LatteCostProbe_OnFrameEnd()
{
	if (s_phase == Phase::IDLE)
	{
		if (s_requested.exchange(false))
			StartRun();
		return;
	}

	const auto now = std::chrono::steady_clock::now();
	const double frameMs = std::chrono::duration<double, std::milli>(now - s_lastFrame).count();
	s_lastFrame = now;
	s_phaseFrame++;

	switch (s_phase)
	{
	case Phase::WAITING:
		if (s_phaseFrame >= kStartDelayFrames)
		{
			s_phase = Phase::COLLECTING;
			s_phaseFrame = 0;
		}
		break;
	case Phase::COLLECTING:
		if (s_phaseFrame >= kCollectFrames)
			PlanSteps();
		break;
	case Phase::TESTING:
		TimeFrame(frameMs);
		break;
	default:
		break;
	}
}
