#pragma once

void KeyCache_Prepare();
// keys.txt is read again the next time keys are needed
void KeyCache_Reload();

uint8* KeyCache_GetAES128(sint32 index);