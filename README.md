# Floating Ghost Image

A lightweight Android overlay app that displays a semi-transparent reference image on top of other apps. Perfect for drawing, tracing, comparing images, or any task requiring a visual reference overlay.

<p float="left">
  <img src="https://github.com/user-attachments/assets/e1182082-ac01-48ca-aa2c-736a721d0870" width="45%" /> 
  <img src="https://github.com/user-attachments/assets/4b97c20d-b975-4fd4-9c53-18bbc432912e" width="45%" />
</p>

## Features

- **Floating Overlay**: Display any image as a semi-transparent overlay on top of other apps
- **Adjustable Opacity**: Control transparency from 0% to 100%
- **Lock Mode**: Enable passthrough to interact with apps below the overlay
- **Edit Mode**: Drag, zoom, rotate, and position the image
- **Share Integration**: Start overlay directly from any app's share menu
- **Minimal Size**: Optimized APK under 2MB
- **Clean UI**: Only used XML/Kotlin

## Requirements

- Android 7.0 (API 24) or higher
- Overlay permission (requested on first use)

## Installation

<a href="https://play.google.com/store/apps/details?id=com.rvoidex7.floatingghostimage" target="_blank">
<img width="199" height="77" alt="playstore(2)" src="https://github.com/user-attachments/assets/4b5b8a37-4552-49f6-af59-b5bf6b9a26ea" />
</a>


## Usage

### Starting the Overlay

1. Open **Floating Ghost Image**
2. Tap the image preview area to select an image
3. Tap the **START** button
4. Grant overlay permission if prompted
5. The app minimizes and shows a small floating icon
6. Tap the icon to show/hide the image

### Editing the Overlay

1. **Long press** the floating icon to open controls
2. **Drag** the icon to reposition it
3. Use the **opacity slider** to adjust transparency
4. Toggle the **lock switch**:
   - **ON (locked)**: Image is passthrough - touches go through to apps below
   - **OFF (unlocked)**: You can drag, zoom, and rotate the image
5. Tap the **back button** to return to icon-only mode
6. Tap the **close button** to stop the overlay

### Using with Image Sharing

1. Open any app with an image (Gallery, Browser, etc.)
2. Tap the **Share** button
3. Select **Floating Ghost Image** from the share menu
4. The overlay starts automatically with your image

## Controls

- **Floating Icon**: Tap to show/hide image, long press to edit, drag to move
- **Opacity Slider**: Adjust image transparency (0-100%)
- **Lock Switch**: Enable/disable passthrough mode
- **Back Button**: Return to icon-only mode
- **Close Button**: Stop the overlay completely

## Optimization Features

This app has been heavily optimized for minimal APK size:

- ProGuard/R8 code shrinking and obfuscation
- Resource shrinking (removes unused resources)
- Removed xxxhdpi and xxhdpi icon densities
- Vector drawables only (no PNG assets)
- Minimal dependencies (only essential AndroidX libraries)
- Aggressive ProGuard rules (removes debug logging)
- Native library compression disabled (`extractNativeLibs=false`)
- Build features optimization (disabled unused features)

## Technical Details

- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 36 (Android 15)
- **Language**: Kotlin
- **Architecture**: Single activity + foreground service
- **Permissions**: `SYSTEM_ALERT_WINDOW` (overlay permission)

## Project Structure

```
app/
├── src/main/
│   ├── java/com/rvoidex7/floatingghostimage/
│   │   ├── MainActivity.kt          # Main UI screen
│   │   └── FloatingImageService.kt  # Overlay service
│   ├── res/
│   │   ├── drawable/                # Vector drawables
│   │   ├── layout/                  # XML layouts (2 files)
│   │   ├── mipmap-*/                # App icons (mdpi to xhdpi)
│   │   └── values/                  # Colors, strings, themes
│   └── AndroidManifest.xml
├── build.gradle.kts                 # App-level build config
└── proguard-rules.pro               # ProGuard optimization rules
```

## Building from Source

### Prerequisites

- Android Studio Arctic Fox or newer
- JDK 11 or higher
- Android SDK with API 36

### Setup

1. Clone the repository:
```bash
git clone https://github.com/rvoidex7/floating-ghost-image.git
cd floating-ghost-image
```

2. Create `gradle.properties` file:
```bash
# Copy the example file
cp gradle.properties.example gradle.properties
```

The example file contains all necessary Gradle settings. **Release signing configuration is only needed by the maintainer** - contributors can skip those lines and use debug builds.

### Build Steps

```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Run on connected device
./gradlew installDebug
adb shell am start -n com.rvoidex7.floatingghostimage/.MainActivity
```

The debug APK will be located at `app/build/outputs/apk/debug/app-debug.apk`

**Note**: Release builds are signed by the maintainer. Contributors should use debug builds for development and testing.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is open source and available under the [MIT License](LICENSE).

## Changelog

### Version 1.0 (Current)
- Initial release
- Basic overlay functionality
- Lock/Edit mode toggle
- Image sharing support
- Size optimizations (APK under 2MB)

## Support

- **GitHub Issues**: [Report a bug](https://github.com/rvoidex7/floating-ghost-image/issues)
- **GitHub Discussions**: [Ask questions](https://github.com/rvoidex7/floating-ghost-image/discussions)

## Credits

Developed by [@rvoidex7](https://github.com/rvoidex7) <br>
<br>
<a href="https://buymeacoffee.com/ykpkrmzcn53" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

## Privacy

This app:
- Does NOT collect any user data
- Does NOT require internet permission
- Does NOT use analytics or tracking
- Only accesses images you explicitly select
- Stores no data outside of temporary image URIs

All processing happens locally on your device.

