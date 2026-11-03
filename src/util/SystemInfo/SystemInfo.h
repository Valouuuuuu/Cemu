#pragma once

struct ProcessorTime
{
	uint64 idle{}, kernel{}, user{};
	
	uint64 work();
	uint64 total();

	static double Compare(ProcessorTime &last, ProcessorTime &now);
};

uint32 GetProcessorCount();
uint64 QueryRamUsage();
void QueryProcTime(uint64 &out_now, uint64 &out_user, uint64 &out_kernel);
void QueryProcTime(ProcessorTime &out);
void QueryCoreTimes(uint32 count, std::vector<ProcessorTime>& out);

#if BOOST_PLAT_ANDROID
// keeps the calling thread on the fastest cores of a big.LITTLE processor, returns how many there are (0 if nothing was changed)
uint32 RunCurrentThreadOnFastCores();
#endif
