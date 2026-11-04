#include "Cafe/HW/Latte/Core/Latte.h"
#include "Cafe/HW/Latte/Core/LatteShader.h"
#include "Cafe/HW/Latte/Core/LatteOverlay.h"
#include "Cafe/HW/Latte/Core/LatteCostProbe.h"

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <map>
#include <mutex>
#include <numeric>
#include <optional>
#include <sstream>

std::atomic_bool g_latteCostProbeActive = false;

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
	constexpr size_t kResultLines = 24;

	constexpr uint8 kEffectHalfRate = 1;
	constexpr uint8 kEffectHidden = 2;

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
	std::atomic_bool s_analysisRunning = false;
	std::mutex s_reportMutex;
	std::string s_report = "Pas encore d'analyse.";
	std::string s_results;

	std::mutex s_effectsMutex;
	std::map<ShaderKey, uint8> s_effects;
	std::atomic_bool s_hasEffects = false;

	uint32 s_frameCount = 0;
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

	std::optional<ShaderKey> ParseKey(const std::string& text)
	{
		const size_t separator = text.find('_');
		if (separator == std::string::npos)
			return std::nullopt;
		try
		{
			return ShaderKey{std::stoull(text.substr(0, separator), nullptr, 16), std::stoull(text.substr(separator + 1), nullptr, 16)};
		}
		catch (const std::exception&)
		{
			return std::nullopt;
		}
	}

	void UpdateActive()
	{
		g_latteCostProbeActive = s_analysisRunning || s_hasEffects;
	}

	void SetReport(std::string text)
	{
		std::lock_guard lock(s_reportMutex);
		s_report = std::move(text);
	}

	void SetResults(std::string list)
	{
		std::lock_guard lock(s_reportMutex);
		s_results = std::move(list);
	}

	void StartRun()
	{
		s_phase = Phase::WAITING;
		s_phaseFrame = 0;
		s_lastFrame = std::chrono::steady_clock::now();
		s_stats.clear();
		s_steps.clear();
		s_hidden.reset();
		s_analysisRunning = true;
		UpdateActive();
		SetReport("Analyse en cours : elle commence dans quelques secondes. Reste dans le jeu.");
		LatteOverlay_pushNotification("Analyse des FPS dans quelques secondes : reste dans le jeu, sans bouger. L'image va clignoter pendant environ 3 minutes.", 8000);
	}

	void EndRun()
	{
		s_hidden.reset();
		s_phase = Phase::IDLE;
		s_analysisRunning = false;
		UpdateActive();
	}

	void Finish()
	{
		EndRun();

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
		std::string list = fmt::format("{:.1f}\t{:.1f}\n", referenceMs, 1000.0 / referenceMs);
		for (size_t i = 0; i < results.size() && i < std::max(kReportLines, kResultLines); i++)
		{
			const Result& result = results[i];
			const double hiddenMs = referenceMs - result.gainMs;
			const double hiddenFps = hiddenMs > 1.0 ? 1000.0 / hiddenMs : 0.0;
			const double drawsPerFrame = (double)result.stats.draws / kCollectFrames;
			if (i < kReportLines)
				report += fmt::format("{:2}. {:+.1f} ms ({:.0f} images/s)  {}  {:.1f} dessins/image\n", i + 1, -result.gainMs, hiddenFps, Describe(result.key), drawsPerFrame);
			if (i < kResultLines)
				list += fmt::format("{:016x}_{:016x}\t{:.1f}\t{:.0f}\t{:.1f}\n", result.key.first, result.key.second, result.gainMs, hiddenFps, drawsPerFrame);
		}
		SetReport(report);
		SetResults(list);
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
			EndRun();
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
	if (!s_analysisRunning)
		s_requested = true;
}

std::string LatteCostProbe_GetReport()
{
	std::lock_guard lock(s_reportMutex);
	return s_report;
}

std::string LatteCostProbe_GetResults()
{
	std::lock_guard lock(s_reportMutex);
	return s_results;
}

void LatteCostProbe_SetEffects(const std::string& effects)
{
	std::map<ShaderKey, uint8> parsed;
	std::istringstream stream(effects);
	std::string item;
	while (std::getline(stream, item, ','))
	{
		const size_t colon = item.find(':');
		if (colon == std::string::npos)
			continue;
		const std::optional<ShaderKey> key = ParseKey(item.substr(0, colon));
		const int mode = std::atoi(item.c_str() + colon + 1);
		if (key && (mode == kEffectHalfRate || mode == kEffectHidden))
			parsed[*key] = static_cast<uint8>(mode);
	}
	{
		std::lock_guard lock(s_effectsMutex);
		s_effects = std::move(parsed);
		s_hasEffects = !s_effects.empty();
	}
	UpdateActive();
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
	const ShaderKey key = KeyOf(pixelShader);
	if (s_hidden.has_value() && *s_hidden == key)
		return true;
	if (!s_hasEffects)
		return false;
	std::lock_guard lock(s_effectsMutex);
	const auto effect = s_effects.find(key);
	if (effect == s_effects.end())
		return false;
	return effect->second == kEffectHidden || (effect->second == kEffectHalfRate && (s_frameCount & 1) != 0);
}

void LatteCostProbe_OnFrameEnd()
{
	s_frameCount++;
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
