# Q25 Trackpad Customizer

Android app (Accessibility Service) for Zinwa Q25.

## Fork note

This repository is a custom fork of [shroyertech/Q25TrackpadCustomizer](https://github.com/shroyertech/Q25TrackpadCustomizer), based on the latest upstream release at the time this fork was started.

Main changes in this fork:
- Added `Scroll mode 2` as a separate low-level touch-scroll mode
- Added optional trackpad-press mode switching
- Added optional mouse-mode tap-to-click heuristic
- Added separate sensitivity controls for cursor mode, scroll wheel mode, and `Scroll mode 2`
- Added a persistent mode notification option
- Removed old per-app settings UI and other stale configuration paths to match the current global-only runtime

## Features
- Global mode selection:
  - Mouse
  - Keyboard
  - Scroll wheel
  - Scroll mode 2
- Quick toggle cycle selection with single-mode fallback
- Scroll wheel customization:
  - sensitivity
  - invert vertical
  - invert horizontal
  - optional horizontal scrolling
- Separate cursor sensitivity setting
- Separate `Scroll mode 2` sensitivity setting
- Optional trackpad press mode switching
- Optional mouse-mode tap-to-click heuristic
- Persistent mode notification option
- Broadcast receiver for smooth Key Mapper integration
- Backup & restore settings
- Initial setup helpers (Settings page)
  - Test / Grant Root Access (`su -c id`)
  - Guided Accessibility setup (Restricted Settings flow)

## Download
- Source: [astroboii47/Q25TrackpadCustomizer](https://github.com/astroboii47/Q25TrackpadCustomizer)
- Upstream source: [shroyertech/Q25TrackpadCustomizer](https://github.com/shroyertech/Q25TrackpadCustomizer)
- Releases for this fork should be published [here](https://github.com/astroboii47/Q25TrackpadCustomizer/releases)

## Setup / Usage

🚨 **Prerequisite: This app requires root!** 🚨 
The app also requires enabling its Accessibility Service.

### 1) Install
Install the APK from the Releases page.

### 2) Enable the Accessibility Service (Restricted Settings flow)
Android will require allowing “restricted settings” before the Accessibility toggle can be enabled.

1. Go to **Settings → Accessibility**
2. Tap **Q25 Trackpad Customizer** (it may be grayed out)
   - This will show a **"Restricted settings"** warning
3. Go to **Settings → Apps → Q25 Trackpad Customizer**
4. Tap the **⋮ (3 dots)** menu (top-right) → **Allow restricted settings**
5. Enter your PIN/passcode
6. Go back to **Settings → Accessibility → Q25 Trackpad Customizer → Enable**

> Tip: The app includes a step-by-step setup wizard and a button in Settings (**Initial setup > Accessibility service setup**) that guides you through the above steps.

### 3) Grant Root Access
Grant root access in your root manager (KernelSU, Magisk, etc).

- The app requests root using: `su -c id`
- This serves as both a "check" for root access, and also would prompt apps like Magisk to allow root access if not already granted.
- If you don’t see a prompt or you previously denied it, you can re-check anytime:
  - **Settings → Initial setup → Test / Grant Root Access**

## Quick Toggle

Quick Toggle can be triggered from:
- the built-in shortcut/activity
- the broadcast receiver for apps like Key Mapper
- the trackpad press itself, if you enable that option in Settings

### Mode selection
Choose what Quick Toggle cycles through:
- Mouse
- Keyboard
- Scroll Wheel
- Scroll mode 2

- If you pick multiple modes, Quick Toggle cycles through only those modes.
- If only one mode is selected, Quick Toggle uses the selected Single-Mode Fallback so it doesn’t “do nothing.”

### Single-Mode Fallback (Global Setting; only applies when only 1 mode is selected)
Example: Quick Toggle is set to **Keyboard only**
  - In Mouse/Scroll Wheel/Scroll mode 2 > Quick Toggle switches to Keyboard
  - Already in Keyboard mode > Quick Toggle switches to your selected Fallback mode

### Default mode is always part of the cycle
The app’s global default mode is always reachable.

## Notes
- This app uses an AccessibilityService to monitor the foreground app and focused views.
- Scroll wheel mode maps DPAD keys (keyboard mode) to scroll gestures.
- `Scroll mode 2` uses a low-level helper path and requires root.
- The current fork is tuned for global/manual mode switching rather than per-app behavior.

## FAQ

### Why did mode switching or scrolling stop working after updating?
You need to re-enable the Accessibility Service after updating:
- **Settings > Accessibility > Q25 Trackpad Customizer > Enable**

### How can I disable the pop ups when it switches modes?
You can disable toasts individually on the Settings page, or enable the persistent mode notification instead.

### How do I use this with Key Mapper?
Use the broadcast receiver action:

`tech.shroyer.q25trackpadcustomizer.action.QUICK_TOGGLE`

Package:

`tech.shroyer.q25trackpadcustomizer`

Receiver/class:

`tech.shroyer.q25trackpadcustomizer.QuickToggleReceiver`

This is lighter than launching the activity-based shortcut.

### What is Scroll mode 2?
`Scroll mode 2` is the low-level touch-scroll mode added in this fork. It is intended to feel more like direct screen swiping than the original scroll wheel mode.

### Why doesn’t Magisk prompt me to allow root?
Magisk typically prompts only when the app actually runs `su`. Use:
- **Settings > Initial setup > Test / Grant Root Access**

If you previously denied root and Magisk remembered it, re-enable it in:
- **Magisk > Superuser > Q25 Trackpad Customizer > Allow**

### I don’t see the “⋮ / Allow restricted settings” option in App Info
You likely missed the step where you tap the app entry in **Accessibility** first (that’s what triggers the restricted warning).  
Go back to:
- **Settings > Accessibility > Q25 Trackpad Customizer**  
Then return to App Info and check again.

## License
See [LICENSE](LICENSE).

## Donations
- If you feel this app has improved your experience with your Q25, consider donating to the WVU Cancer Institute
- You can make a donation [here](https://give.wvu.edu/give/430764/#!/donation/checkout)
- You can read more about the WVU Cancer Institute [here](https://give.wvu.edu/campaign/wvu-cancer-institute/c430764)
- Thank you <3

## Attributions
- App icon created by WhoCon from [FlatIcon](https://www.flaticon.com/free-icons/toggle)
