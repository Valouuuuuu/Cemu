#pragma once
#include "Cafe/OS/RPL/COSModule.h"

namespace nsyshid
{
	class Backend;

	void AttachBackend(const std::shared_ptr<Backend>& backend);
	void DetachBackend(const std::shared_ptr<Backend>& backend);

	// for an emulated device that the game does not have yet: it sees it as just plugged in
	void AttachMissingDevices();

	COSModule* GetModule();
} // namespace nsyshid
