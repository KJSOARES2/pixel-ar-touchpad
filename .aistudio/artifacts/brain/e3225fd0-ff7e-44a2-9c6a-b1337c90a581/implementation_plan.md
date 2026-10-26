# Pixel AR Touchpad — Google Play Accessibility Prominent Disclosure

Adds a Google Play Policy-compliant **Accessibility API Prominent Disclosure & Consent Dialog** while preserving the write-only `TouchpadAccessibilityService` for cross-display USB-C AR glasses gesture dispatch and the full-screen hardware-accelerated `DeXCrosshairTrackpadView`.

## User Review & Critical Decisions

> [!IMPORTANT]
> Confirmed from your selection:

- **Confirmed Decision — Keep `AccessibilityService` + Add Google Play Prominent Disclosure Dialog**:
  - Retains `TouchpadAccessibilityService` (`canPerformGestures="true"`, `canRetrieveWindowContent="false"`, `accessibilityEventTypes=""`) so the touchpad can inject 1-finger Left Clicks, 3-finger Right Clicks, and 2-finger Scrolls onto external USB-C AR displays (`Display.FLAG_PRESENTATION`).
  - Adds a dedicated **Prominent Disclosure Modal Dialog** meeting Google Play's Accessibility API User Data & Prominent Disclosure requirements before navigating to system Accessibility Settings.

---

## 1. Overview & Core Concept

- **What It Does**: Displays a clear, standalone Google Play-compliant Prominent Disclosure dialog explaining why `AccessibilityService` is used, what data it accesses (none — strictly write-only gesture dispatch), and requires explicit user consent (**Accept & Enable** vs. **Decline / Not Now**) before opening `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
- **Target Audience / Persona**: Google Pixel Desktop Mode + USB-C AR glasses users and Google Play Store reviewers verifying Accessibility API compliance.
- **Key Value**:
  - **Google Play Review Compliance**: Satisfies Google Play's requirement that apps using `BIND_ACCESSIBILITY_SERVICE` present an in-app prominent disclosure stating the use of the AccessibilityService API, its assistive/controller purpose, and its zero-data-collection guarantee prior to enabling the service.
  - **Zero Clutter on the Trackpad**: Keeps the main touchpad screen completely clean (no status banner, no bottom text, no `(x, y)` top-bar coordinate updates), while making the Accessibility Disclosure & Setup accessible on first launch (when the service is not yet enabled) and inside the Calibration bottom sheet.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **First-Launch Prominent Disclosure Flow**:
   - When the app launches and `TouchpadAccessibilityService` is not currently active (and the user has not dismissed the prompt for the session), a Material 3 **Accessibility Service Prominent Disclosure Dialog** appears over the touchpad.
   - The dialog clearly presents three structured disclosure points:
     1. **Why Accessibility Service Is Used**: To dispatch touch clicks, 3-finger right-clicks, and 2-finger scroll gestures onto external USB-C DisplayPort Alt-Mode AR glasses displays (`Display.FLAG_PRESENTATION`) for eyes-free, single-handed desktop control.
     2. **Strictly Write-Only Operation**: Configured with `canRetrieveWindowContent="false"` and `accessibilityEventTypes=""`. The app **never** reads screen content, UI text, keystrokes, or personal information.
     3. **Zero Permissions & Zero Data Sharing**: The app declares **zero** `<uses-permission>` tags (no `INTERNET` permission) and never collects, stores, or transmits any user data off-device.
   - **Action Buttons**:
     - **Accept & Open Settings**: Records user consent and launches `Settings.ACTION_ACCESSIBILITY_SETTINGS` so the user can toggle on **Pixel AR Touchpad Controller**.
     - **Not Now (Decline)**: Dismisses the dialog immediately without opening system settings so the user can still inspect or use the touchpad surface and calibration controls.
2. **Calibration Bottom Sheet Access**:
   - Inside the Calibration bottom sheet (opened via the top-right tune icon), a compact **External AR Display Dispatch (Accessibility API)** status row shows whether the write-only service is currently **Active** or **Inactive**, and allows re-opening the Prominent Disclosure Dialog at any time.

### Visual Identity & Theme
- Matches the existing AMOLED Slate (`#0F172A` dialog surface, `#1E293B` outline border, `#38BDF8` sky-cyan primary action button, `#818CF8` indigo badge accents, and `Space Grotesk` / `JetBrains Mono` typography).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Standalone Modal Dialog with Explicit Accept / Decline Actions**
  - *Chosen Approach*: Use an unmistakable Material 3 `AlertDialog` that cannot be confused with generic terms-of-service text, containing explicit **Accept & Open Settings** and **Not Now** buttons.
  - *Why*: Google Play policy strictly requires that Prominent Disclosures be presented independently of privacy policies/ToS and require an affirmative user action before redirecting to system Accessibility Settings.
- **Decision 2: Keep Main Touchpad Canvas Free of Persistent Banners**
  - *Chosen Approach*: Do not re-add any persistent banner or bottom text to the main touchpad screen; place the secondary trigger to review/enable the Accessibility Service inside the Calibration bottom sheet.
  - *Why*: Preserves the clean, distraction-free full-screen DeX touchpad layout you requested.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        MainActivity (FLAG_KEEP_SCREEN_ON)                    │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ Minimalist Top Bar (App Title + Calibration Sheet Button)              │  │
│  └───────────────────────────────────┬────────────────────────────────────┘  │
│                                      │                                       │
│  ┌───────────────────────────────────▼────────────────────────────────────┐  │
│  │ DeXCrosshairTrackpadView (Full-Screen Hardware-Accelerated View)       │  │
│  │  • Hardware-Accelerated Path Ring Buffer (4 Decay Bands + Bezier)      │  │
│  │  • 1F Move/Tap (Left Click) | 2F Drag (Scroll) | 3F Tap (Right Click)  │  │
│  └───────────────┬────────────────────────────────────────┬───────────────┘  │
│                  │                                        │                  │
│  ┌───────────────▼───────────────────┐    ┌───────────────▼───────────────┐  │
│  │ Play Policy Prominent Disclosure  │    │ CalibrationBottomSheet        │  │
│  │ Dialog (Compose AlertDialog)      │◄───│  • Sensitivity & Scroll Speed │  │
│  │  • Explains dispatchGesture usage │    │  • Natural Scroll & Tap-Click │  │
│  │  • Affirms Write-Only & 0-Perm    │    │  • A11y Disclosure & Status   │  │
│  │  • [Accept & Enable] / [Not Now]  │    └───────────────────────────────┘  │
│  └───────────────┬───────────────────┘                                       │
└──────────────────┼───────────────────────────────────────────────────────────┘
                   │ Affirmative Consent -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│            TouchpadAccessibilityService (Write-Only, Exported=false)         │
│  • DisplayManager.DisplayListener (Detects FLAG_PRESENTATION / displayId!=0) │
│  • Dispatches GestureDescription via reusablePath to External AR displayId   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Interactive Component & State Mapping
- **`isDisclosureDialogOpen: Boolean` in `TouchpadUiState`**:
  - Initialized on launch: if `!TouchpadAccessibilityService.isServiceConnected.value` and the user hasn't dismissed it this session, opens the Prominent Disclosure Dialog automatically.
  - Clicking **Accept & Open Settings** closes the dialog and launches `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`.
  - Clicking **Not Now** closes the dialog and keeps the user on the touchpad screen.
  - Clicking **Accessibility Disclosure & Setup** inside `CalibrationBottomSheet` opens the Prominent Disclosure Dialog on demand.
