#pragma once

#include <atomic>
#include <string>

struct LatteDecompilerShader;

// Finds which effects cost the most. The pixel shaders drawn most often are hidden one at a time while the frames are timed.
// Asking for a run and reading the report can be done from any thread, everything else belongs to the GPU thread.

extern std::atomic_bool g_latteCostProbeRunning;

inline bool LatteCostProbe_IsRunning()
{
	return g_latteCostProbeRunning.load(std::memory_order_relaxed);
}

void LatteCostProbe_Request();
std::string LatteCostProbe_GetReport();

void LatteCostProbe_CountDraw(const LatteDecompilerShader* pixelShader, uint32 vertexCount);
bool LatteCostProbe_ShouldHide(const LatteDecompilerShader* pixelShader);
void LatteCostProbe_OnFrameEnd();
