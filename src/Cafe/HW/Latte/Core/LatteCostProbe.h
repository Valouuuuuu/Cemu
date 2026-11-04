#pragma once

#include <atomic>
#include <string>

struct LatteDecompilerShader;

// Finds which effects cost the most, and lets some of them be hidden or drawn every other frame.
// The pixel shaders drawn most often are hidden one at a time while the frames are timed.
// Asking for a run, choosing effects and reading the results can be done from any thread, everything else belongs to the GPU thread.

extern std::atomic_bool g_latteCostProbeActive;

// whether a run is going on or some effects are chosen: the drawing only calls the functions below then
inline bool LatteCostProbe_IsActive()
{
	return g_latteCostProbeActive.load(std::memory_order_relaxed);
}

void LatteCostProbe_Request();
std::string LatteCostProbe_GetReport();

// the result of the last run: the reference frame time and speed on the first line, then for each effect its name,
// the time it costs per frame, the speed without it and how many times it is drawn per frame, separated by tabs
std::string LatteCostProbe_GetResults();

// the effects to change, as a list of "name:mode" separated by commas. Mode 1 draws an effect every other frame, mode 2 hides it
void LatteCostProbe_SetEffects(const std::string& effects);

void LatteCostProbe_CountDraw(const LatteDecompilerShader* pixelShader, uint32 vertexCount);
bool LatteCostProbe_ShouldHide(const LatteDecompilerShader* pixelShader);
void LatteCostProbe_OnFrameEnd();
