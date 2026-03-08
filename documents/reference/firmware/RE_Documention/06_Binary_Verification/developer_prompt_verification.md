# Android Auto First-Pairing — Binary Verification (2026-03-05)

## Overview
Verification of claims regarding Android Auto first-pairing behavior on the CPC200-CCPA adapter.
All findings verified against disassembled ARMadb-driver_2025.10 (unpacked ELF 32-bit ARM)
and bluetoothDaemon using radare2.

For full binary analysis of the underlying systems, see:
- AndroidWorkMode deep analysis: `05_Reference/binary_analysis/config_key_analysis.md` §[27]
- iPhone/Android mode enumerations: `02_Protocol_Reference/usb_protocol.md` §iPhone/Android Work Mode Enumerations
- RiddleLinkType enum: `02_Protocol_Reference/device_identification.md` §RiddleLinkType Enum
- ProceessCmdOpen dispatch: `02_Protocol_Reference/usb_protocol.md` §ProceessCmdOpen Work-Mode Dispatch
- UNKOWN strings: `05_Reference/binary_analysis/key_binaries.md` §Firmware "UNKOWN?" Fallback Strings
- HULinkType mismatch: `05_Reference/binary_analysis/config_key_analysis.md` §[66] CarLinkType Side Effects
- Huawei VID check: `02_Protocol_Reference/device_identification.md` §Huawei VID Check
- First connection sentinel: `02_Protocol_Reference/device_identification.md` §First HU Connection Sentinel

---

## Summary

| # | Statement | Verdict | Key Evidence |
|---|-----------|---------|--------------|
| 1 | AndroidWorkMode is a general "android enabled" flag | **INCORRECT** | 6-mode daemon selector (0-5); `cmp` chain at 0x177b4-0x177c8 in `fcn.0001777c` |
| 2 | PhoneWorkMode controls CarPlay↔Android stack | **INCORRECT** | `phoneMode` (Open msg offset 0x18) → `OniPhoneWorkModeChanged` (iPhone only); Android reads `/etc/android_work_mode` separately |
| 3 | Default is CarPlay mode; AA fails on first pair | **CONFIRMED** | Both modes reset to 0 on disconnect (`fcn.00017940`); AA daemon not started without explicit `androidWorkMode=1` |
| 4 | `RiddleLinktype_UNKOWN?` indicates unknown phone | **INCORRECT** | Enum-to-string fallback in MDLinkType JSON reporter; 5 distinct UNKOWN? strings exist (see key_binaries.md) |
| 5 | Mode values 2↔4 select the Android stack | **INCORRECT** | 2=CarLife, 4=HiCar; Android Auto is mode **1** |
| 6 | Device added to riddleCfg as AndroidAuto | **CONFIRMED** | bluetoothDaemon `SaveDevList` with type strings at 0x5de0c/0x5de24 |
| 7 | Connection speed-up via last-device mode | **CONFIRMED** | FastConnect + AutoConnect_By_BluetoothAddress; already implemented in carlink_native |
| 8 | AA supports 3+2 resolutions (1440p/4K wireless) | **UNVERIFIABLE** | Firmware has no tier checks (max=4096); ARMAndroidAuto is packed — tier enforcement is in AA protocol |

---

## Corrections

### AndroidWorkMode is NOT a Boolean Flag

`OnAndroidWorkModeChanged` (`fcn.0001777c`) contains two `cmp`/`beq` switch chains — one for stopping the old daemon, one for starting the new:

```arm
; START new daemon (r4 = new mode):
0x177ec  cbz r4, 0x1782e         ; mode 0 → skip (disconnected)
0x177ee  cmp r4, 1; beq 0x1780a  ; → ldr r2, "AndroidAuto"
0x177f2  cmp r4, 2; beq 0x1780e  ; → ldr r2, "CarLife"
0x177f6  cmp r4, 3; beq 0x17812  ; → ldr r2, "AndroidMirror"
0x177fa  cmp r4, 4; beq 0x17816  ; → ldr r2, "HiCar"
0x17802  cmp r4, 5; it eq        ; → mov r2, "ICCOA"
default: ldr r2, "AndroidWorkMode_UNKOWN?"
0x17818  ldr r1, "/script/phone_link_deamon.sh %s start &"
0x17822  blx system
```

Each mode value starts a **different daemon binary**. Setting it to 1 specifically selects `ARMAndroidAuto` — not "enables the android stack."

### PhoneWorkMode Controls iPhone Stack Only

`ProceessCmdOpen` (`fcn.00021cb0`) at `0x21e52`:
```arm
0x21e52  ldr r0, [r6, 0x18]   ; phoneMode from Open msg
0x21e54  bl fcn.000176bc        ; OniPhoneWorkModeChanged → AirPlay/CarPlay/iOSMirror/OnlyCharge
0x21e58  bl fcn.00016640        ; read /etc/android_work_mode
0x21e5e  bl fcn.0001777c        ; OnAndroidWorkModeChanged → AA/CarLife/Mirror/HiCar/ICCOA
```

Two fully independent systems. `phoneMode` in Open controls iPhone daemons only. The Android stack reads its mode from a separate persisted file written via BoxSettings.

### RiddleLinktype_UNKOWN? is an Enum Fallback, Not a Detection Signal

The string at `0x0006da49` is the **default case** in a 10-way switch inside `fcn.00019978` (MDLinkType JSON reporter). It maps the numeric `RiddleLinkType` global to a string for the `{"MDLinkType":"%s",...}` JSON blob. Values 1-8 and 0x1E have specific mappings; any other value falls through to this string. It is never emitted as a detection signal for an unknown phone — it appears only when the global link type register contains an unrecognized value.

The actual mismatch detection strings are:
- `"Detect HULinktype changed by usb device plugin!!"` (0x00071653)
- `"ResetConnection by HULink not match!!!"` (0x0006f901)

### Correct Mode Values for Android Auto

From `0x1780a-0x17816`:
```
mode 1 → "AndroidAuto"    (string @ 0x701c2)
mode 2 → "CarLife"         (string @ 0x701af)
mode 3 → "AndroidMirror"   (string @ 0x6cc0b)
mode 4 → "HiCar"           (string @ 0x800a5)
mode 5 → "ICCOA"           (string @ 0x6cc05)
```

Switching 2↔4 toggles **CarLife** (Baidu) ↔ **HiCar** (Huawei). Android Auto is mode **1**.

---

## carlink_native Status

All verified behavior is already correctly handled:
- Sends `androidWorkMode=true` on every init via `MessageSerializer.kt:383-385`
- Sends `phoneWorkMode=2` (CarPlay) in Open message via `MessageTypes.kt:462`
- Parses DevList from BoxSettings for hot-rejoin via `CarlinkManager.kt:1639-1647`
- Clamps AA resolution to 3 confirmed tiers via `MessageSerializer.kt:249-261`

No code changes required based on these findings.
