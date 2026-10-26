# Pixel AR Touchpad

A precision hardware-accelerated touchpad and spatial desktop controller built for Google Pixel Desktop Mode with USB-C DisplayPort Alt-Mode AR glasses (XREAL Air 2 Pro, VITURE Pro XR, Rokid Max, RayNeo Air 2s), featuring an interactive 1920x1080 Virtual AR Display Simulator for full testing inside the Android emulator.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product and design choices have been confirmed from your preferences and integrated with the core hardware specification:

- **Confirmed Decision 1 — Virtual AR Display Simulator Default State**: The split-screen **Virtual AR Display Simulator** opens **by default** when the app launches so you can immediately test pointer tracking, clicking, two-finger scrolling, right-click context menus, and 3-finger Task Overview in the emulator even when no physical USB-C AR glasses are attached. It can be toggled open or collapsed at any time from the top bar.
- **Confirmed Decision 2 — Multi-Window AR Workspace Environment**: The 1920x1080 Virtual AR Display viewport renders an interactive multi-window spatial workspace featuring three live windows (**AR Browser** with scrollable article feed & links, **Terminal** with scrollable command history & quick action buttons, and **Spatial Media Deck** with interactive playback controls and playlist), plus a right-click context menu and a 3-finger swipe-up **Desktop Task Overview** grid.
- **Confirmed Decision 3 — Streamlined Calibration Bottom Sheet**: The Calibration bottom sheet focuses cleanly on **Pointer Sensitivity** (`0.5x – 3.0x`, default `1.4x`), **Scroll Speed** (`0.5x – 3.0x`, default `1.2x`), **Natural Scrolling** toggle (default `true`), and **Tap-to-Click** toggle (default `true`), with a one-tap reset to DeX defaults.

---

## 1. Overview & Core Concept

- **What It Does**: Transforms a Google Pixel screen into a low-latency, eyes-free spatial trackpad when connected to optical see-through USB-C AR glasses. Every touch gesture on the phone screen dispatches write-only gestures directly to the external AR display via `TouchpadAccessibilityService` while simultaneously driving the built-in 1920x1080 Virtual AR Display Simulator.
- **Target Audience / Persona**: Mobile power users and developers using Google Pixel Desktop Mode with XREAL Air 2 Pro, VITURE Pro XR, Rokid Max, or RayNeo Air 2s glasses who need an ergonomic, zero-glare touchpad without carrying a physical Bluetooth mouse.
- **Key Value**:
  - **OLED Power & Eye Comfort**: Deep slate-black `#08090C` canvas eliminates distracting peripheral light bleed under optical see-through AR lenses.
  - **Zero-Allocation 240Hz Responsiveness**: Processes all historical batched touch samples (`event.historySize`) without object allocations on the `ACTION_MOVE` hot path, paired with a DeX-inspired velocity-adaptive acceleration curve.
  - **Full Emulator Testability**: Works immediately inside the AI Studio Virtual Android Device via the split-screen Virtual AR Display Simulator, while automatically detecting real external `Display.FLAG_PRESENTATION` displays when hardware is plugged in.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Launch & Auto-Detection Flow**:
   - When USB-C AR glasses are connected (or when launched manually), `TouchpadAccessibilityService` detects the presentation display (`Display.FLAG_PRESENTATION` or `displayId != Display.DEFAULT_DISPLAY`) and automatically launches the touchpad on the primary phone screen (`Display.DEFAULT_DISPLAY`).
   - A compact status banner at the top displays the active target display status (either the detected external AR Glasses Display ID & resolution or **Virtual AR Simulator Mode — 1920×1080**), alongside a quick shortcut to enable the Accessibility Service if not yet active in system settings.
2. **Split-Screen Virtual AR Display Simulator Flow**:
   - Positioned in the upper split pane (open by default, collapsible via the top bar), the simulator scales a crisp 1920×1080 AR desktop canvas into the available viewport.
   - Moving 1 finger on the touchpad moves the high-precision AR cursor with DeX dynamic acceleration.
   - **1-finger tap** (`<180ms`, `<10dp` movement) performs a Left Click: focuses windows, brings them to the front, clicks interactive buttons inside the AR Browser, Terminal, or Media Deck, and dismisses open menus.
   - **2-finger tap** (`<210ms`, `<14dp` movement) performs a Right Click: opens a contextual AR desktop action menu at the exact cursor `(x, y)` coordinates (e.g., *New Terminal Tab*, *Refresh Workspace*, *Cascade Windows*, *Toggle Task Overview*).
   - **2-finger drag** scrolls the window currently under the cursor vertically and horizontally (`naturalScrolling = true` by default) with no dedicated scroll wheel or edge scrollbar required.
   - **3-finger swipe up** toggles the **Desktop Task Overview** mode, smoothly arranging all three AR windows into an exposé-style spatial grid for quick window selection.
3. **Eyes-Free Touchpad & Fading Trail Flow**:
   - The lower split pane (or full screen when the simulator is collapsed) hosts `DeXCrosshairTrackpadView`.
   - Single-finger motion leaves a smooth, decaying sky-cyan (`#38BDF8`) vector trail across a 128-sample ring buffer over a `320ms` decay window.
   - Two-finger and three-finger gestures render synchronized dual/triple cyan-indigo (`#38BDF8` / `#818CF8`) trails with glowing contact halos at each active finger position.
   - Subtle tactile ticks are triggered via zero-permission `View.performHapticFeedback()` on taps, right-clicks, and 3-finger Task Overview activation.
4. **Calibration Bottom Sheet Flow**:
   - Tapping the **Tune / Calibrate** icon in the top bar opens a Material 3 modal bottom sheet where users can adjust **Pointer Sensitivity** (`1.4f` default), **Scroll Speed** (`1.2f` default), **Natural Scrolling**, and **Tap-to-Click**, with immediate live updates to both the touchpad engine and simulator.

### Visual Identity & Theme
- **Aesthetic Direction**: Minimalist Samsung DeX-inspired tactical HUD—ultra-dark, geometric, and glare-free.
- **Color Palette & Mood**:
  - **Primary Trackpad Surface**: Deep AMOLED Slate-Black (`#08090C`)
  - **Elevated HUD & Sheet Surface**: Obsidian Slate (`#0F172A`)
  - **Outer Trackpad Border & Dividers**: Crisp Slate Outline (`1dp` `#1E293B`)
  - **Center Crosshair Hairlines**: Subtle Tactical Grid (`#0F172A`)
  - **Primary Accent & 1-Finger Trail**: Sky-Cyan (`#38BDF8`) for `2dp` corner precision L-brackets, center reticle ring, and primary pointer trail
  - **Secondary Multi-Touch Accent**: Electric Indigo (`#818CF8`) for dual/triple-finger gesture trails and contact halos
  - **Typography & Status Tokens**: Crisp Ice-White (`#F8FAFC`) primary labels, Muted Slate (`#94A3B8`) secondary telemetry/subtitles, Emerald (`#34D399`) active display badge
- **Typography & Hierarchy**:
  - Clean geometric sans-serif hierarchy with tabular monospace coordinates and telemetry pills in the HUD header and AR Terminal window.
- **Component Styling & Layout**:
  - Edge-to-edge full-screen layout with `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` so the Pixel display never dims while operating AR glasses.
  - `DeXCrosshairTrackpadView` renders a `1dp` `#1E293B` outer square border flush with the view bounds, accented by `2dp` `#38BDF8` L-brackets at all four corners, edge-to-edge horizontal and vertical center hairlines (`#0F172A`), and a center `#38BDF8` crosshair reticle ring.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Custom Hardware-Accelerated `AndroidView` Canvas for Touchpad Surface**
  - *Chosen Approach*: Implement `DeXCrosshairTrackpadView` as a custom `android.view.View` embedded inside Jetpack Compose via `AndroidView`, backed by pre-allocated `FloatArray` / primitive arrays for the 128-sample ring buffer and pre-allocated `Paint` instances.
  - *Why*: Direct `onTouchEvent(MotionEvent)` and `onDraw(Canvas)` give deterministic 240Hz historical sample processing (`event.historySize`) with zero boxing or garbage collection pauses, calling `postInvalidateOnAnimation()` strictly while trail samples are within their `320ms` decay window.
  - *Alternatives Considered*: Pure Compose `pointerInput` + `Canvas` was passed over for the primary trackpad surface because raw `MotionEvent` batching and explicit zero-allocation ring buffer control are most direct in a custom `View`.
- **Decision 2: Dual-Target Gesture Dispatch (Real External Display + Virtual AR Simulator)**
  - *Chosen Approach*: Route all recognized gestures (`onMove`, `onLeftClick`, `onRightClick`, `onScroll`, `onTaskOverview`) through a unified callback pipeline that updates the in-app 1920×1080 Virtual AR Display Simulator state *and*, whenever `TouchpadAccessibilityService` is bound and an external presentation display is connected, dispatches hardware `GestureDescription` strokes using a single pre-allocated `android.graphics.Path` (`reusablePath.reset()`) targeting the external `displayId` on API 30+.
  - *Why*: Guarantees 100% interactive verification inside the AI Studio emulator while remaining production-ready for physical USB-C AR glasses.
- **Decision 3: Strictly Zero Manifest Permissions & Write-Only Accessibility Hardening**
  - *Chosen Approach*: Omit all `<uses-permission>` declarations from `AndroidManifest.xml`. Use `View.performHapticFeedback()` for haptics, `FLAG_KEEP_SCREEN_ON` for wakefulness, and register `DisplayManager.DisplayListener` directly inside `TouchpadAccessibilityService` (and the foreground Activity for live UI state). Configure `accessibility_service_config.xml` with `canPerformGestures="true"`, `canRetrieveWindowContent="false"`, and `accessibilityEventTypes=""`.
  - *Why*: Eliminates privacy concerns and Play Store permission friction—users can verify that the service is strictly write-only and cannot read screen text or network data.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        MainActivity (FLAG_KEEP_SCREEN_ON)                    │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ Top Bar & AR Display Status Banner (Compose)                           │  │
│  │  • External Display Status / Service Enable Shortcut                   │  │
│  │  • Virtual AR Simulator Toggle (Open by default)                       │  │
│  │  • Calibration Bottom Sheet Trigger                                    │  │
│  └───────────────────────────────────┬────────────────────────────────────┘  │
│                                      │                                       │
│  ┌───────────────────────────────────▼────────────────────────────────────┐  │
│  │ Virtual AR Display Simulator Split-Pane (1920x1080 Logical Viewport)   │  │
│  │  • Multi-Window Workspace: AR Browser | Terminal | Spatial Media Deck  │  │
│  │  • Live DeX Cursor + Right-Click Context Menu + 3-Finger Task Overview │  │
│  └───────────────────────────────────▲────────────────────────────────────┘  │
│                                      │                                       │
│  ┌───────────────────────────────────┴────────────────────────────────────┐  │
│  │ DeXCrosshairTrackpadView (Custom View via Compose AndroidView)         │  │
│  │  • #08090C Surface, 1dp #1E293B Border, 2dp #38BDF8 Corner L-Brackets  │  │
│  │  • #0F172A Center Hairlines & #38BDF8 Center Reticle Ring              │  │
│  │  • 128-Sample Zero-Alloc Ring Buffer (320ms Decay, Cyan/Indigo Trails) │  │
│  │  • 240Hz MotionEvent + historySize Velocity-Adaptive Gesture Engine    │  │
│  └───────────────────────────────────┬────────────────────────────────────┘  │
└──────────────────────────────────────┼───────────────────────────────────────┘
                                       │ Gesture Events (Move/Click/Scroll/Overview)
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│            TouchpadAccessibilityService (Write-Only, Exported=false)         │
│  • DisplayManager.DisplayListener (Detects FLAG_PRESENTATION / displayId!=0) │
│  • Auto-Launches MainActivity on Display.DEFAULT_DISPLAY (0)                 │
│  • Pre-Allocated android.graphics.Path (reusablePath.reset())                │
│  • Dispatches GestureDescription.StrokeDescription to External AR displayId  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **CalibrationConfig**: Holds `sensitivity: Float = 1.4f`, `scrollSpeed: Float = 1.2f`, `naturalScrolling: Boolean = true`, and `tapToClick: Boolean = true`.
- **VirtualArDesktopState**: Holds cursor coordinates `(cursorX, cursorY)` clamped to `1920×1080`, active window z-order and bounds for the three interactive windows (**AR Browser**, **Terminal**, **Spatial Media Deck**), per-window scroll offsets, interactive widget states (active browser tab/article, terminal command log, media play/pause & track index), right-click context menu state `(visible, x, y)`, and `isTaskOverviewOpen: Boolean` (toggled via 3-finger swipe up).
- **Zero-Allocation Trail Ring Buffer**: Primitive arrays inside `DeXCrosshairTrackpadView` (`sampleX`, `sampleY`, `sampleX2`, `sampleY2`, `sampleX3`, `sampleY3`, `sampleTimestamp`, `samplePointerCount`) of capacity `128`, indexed by `headIndex` and `sampleCount`, avoiding object creation during `onTouchEvent` and `onDraw`.

### Interactive Component & State Mapping
- **1-Finger Move (`ACTION_MOVE` + `historySize`)**: Computes instantaneous velocity `(dx, dy) / dt`, applies the DeX velocity-adaptive acceleration multiplier scaled by `sensitivity` (`1.4f`), appends to the 128-sample ring buffer with `pointerCount = 1`, updates the 1920×1080 cursor position, and dispatches a continuous pointer stroke via `TouchpadAccessibilityService` if an external display is active.
- **1-Finger Tap (`<180ms`, `<10dp` movement)**: Triggers `HapticFeedbackConstants.CLOCK_TICK`, dispatches a Left Click at `(cursorX, cursorY)` to focus/click buttons inside the topmost window under the cursor (or select a window in Task Overview), and dispatches a tap stroke to the external AR display.
- **2-Finger Tap (`<210ms`, `<14dp` movement)**: Triggers haptic feedback and opens the Right-Click Context Menu at `(cursorX, cursorY)` in the Virtual AR Simulator while dispatching a secondary gesture/context action to the external display.
- **2-Finger Drag**: Renders dual cyan/indigo trails (`#38BDF8` / `#818CF8`) with contact halos, applies `scrollSpeed` (`1.2f`) and `naturalScrolling` (`true`) to scroll the content of the hovered AR window (Browser feed, Terminal output, or Media playlist), and dispatches a two-finger scroll stroke to the external AR display.
- **3-Finger Swipe Up**: Detects upward 3-finger delta beyond threshold, renders triple trails with contact halos, toggles the **Desktop Task Overview** exposé grid in the Virtual AR Simulator, and dispatches the overview gesture (`GLOBAL_ACTION_RECENTS` / 3-finger upward stroke) on the target display.
