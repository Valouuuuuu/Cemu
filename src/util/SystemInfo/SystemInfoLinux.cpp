#include "util/SystemInfo/SystemInfo.h"

#include <unistd.h>
#if BOOST_PLAT_ANDROID
#include <sched.h>
#endif

uint64 QueryRamUsage()
{
	static long page_size = sysconf(_SC_PAGESIZE);
	if (page_size == -1)
	{
		return 0;
	}

	std::ifstream file("/proc/self/statm");
	if (file)
	{
		file.ignore(std::numeric_limits<std::streamsize>::max(), ' ');
		uint64 pages;
		file >> pages;

		return pages * page_size;
	}
	return 0;
}

void QueryCoreTimes(uint32 count, std::vector<ProcessorTime>& out)
{
	std::ifstream file("/proc/stat");
	if (file)
	{
		file.ignore(std::numeric_limits<std::streamsize>::max(), '\n');

		for (auto i = 0; i < out.size(); ++i)
		{
			uint64 user, nice, kernel, idle;
			file.ignore(std::numeric_limits<std::streamsize>::max(), ' ');
			file >> user >> nice >> kernel >> idle;
			file.ignore(std::numeric_limits<std::streamsize>::max(), '\n');

			out[i].idle = idle;
			out[i].kernel = kernel;
			out[i].user = user + nice;
		}
	}
	else
	{
		for (auto i = 0; i < count; ++i) out[i] = { };
	}
}

#if BOOST_PLAT_ANDROID
uint32 RunCurrentThreadOnFastCores()
{
	// a core that tops out below three quarters of the fastest core's frequency is a slow one
	static const cpu_set_t fastCores = []()
	{
		cpu_set_t cores;
		CPU_ZERO(&cores);
		const uint32 count = std::min<uint32>(GetProcessorCount(), CPU_SETSIZE);
		std::vector<uint64> frequencies(count, 0);
		uint64 fastest = 0;
		for (uint32 i = 0; i < count; ++i)
		{
			std::ifstream file("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq");
			file >> frequencies[i];
			fastest = std::max(fastest, frequencies[i]);
		}
		for (uint32 i = 0; i < count; ++i)
		{
			if (frequencies[i] > 0 && frequencies[i] * 4 >= fastest * 3)
				CPU_SET(i, &cores);
		}
		// all cores are alike, or their frequencies cannot be read: there is nothing to choose from
		if (static_cast<uint32>(CPU_COUNT(&cores)) == count)
			CPU_ZERO(&cores);
		return cores;
	}();

	const uint32 fastCoreCount = static_cast<uint32>(CPU_COUNT(&fastCores));
	if (fastCoreCount == 0 || sched_setaffinity(0, sizeof(cpu_set_t), &fastCores) != 0)
		return 0;
	return fastCoreCount;
}
#endif
