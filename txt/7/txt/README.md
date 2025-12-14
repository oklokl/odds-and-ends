# TXT App

A simple, offline text editor for Android that saves documents as TXT and PDF files.

## Features

- **Create & Edit TXT files** - Simple text editing with auto-save to Downloads folder
- **Export to PDF** - Convert your text documents to PDF format with automatic pagination
- **TXT + PDF Pair Management** - Save both formats together, edit TXT and PDF updates automatically
- **Offline Operation** - No internet connection required, all data stays on your device
- **File Management** - View, rename, and delete your documents from within the app

## Screenshots

*(Add screenshots here)*

## Requirements

- Android 7.0 (API 24) or higher
- No special permissions required (uses MediaStore API)

## Installation

1. Download the APK from the releases page
2. Enable "Install from unknown sources" if prompted
3. Install and open the app

## File Storage

All documents are saved to your device's Downloads folder:
```
/storage/emulated/0/Download/
```

Files are accessible via any file manager app and can be shared, backed up, or deleted freely.

## How to Use

### Creating a New Document
1. Tap the **+** button on the main screen
2. Write your content
3. Tap **Save** and choose:
    - **Save TXT only** - Creates editable .txt file
    - **Save TXT + PDF** - Creates both .txt and .pdf files
    - **Export PDF only** - Creates a new .pdf file

### Editing Existing Documents
- **TXT files**: Tap to open in editor
- **PDF files**: Tap to edit linked TXT (if exists), or tap 👁 icon to view in PDF reader

### Renaming Files
Tap the filename in the editor's top bar to rename

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: Single Activity with Compose Navigation
- **Storage**: MediaStore API (Scoped Storage)
- **PDF Generation**: Android PdfDocument API

## Project Structure

```
app/src/main/java/com/krdondon/txt/
├── MainActivity.kt          # Main entry point & navigation
├── model/
│   └── FileItem.kt          # Data class for file information
├── ui/theme/
│   ├── screens/
│   │   ├── EditorScreen.kt  # Text editing screen
│   │   └── FileListScreen.kt # Document list screen
│   ├── Color.kt
│   ├── Theme.kt
│   └── Type.kt
└── utils/
    ├── FileManager.kt       # File I/O operations
    ├── PdfExporter.kt       # PDF generation
    └── PermissionHandler.kt # Permission utilities
```

## Building from Source

1. Clone the repository
```bash

```

2. Open in Android Studio

3. Build and run
```bash
./gradlew assembleDebug
```

## Privacy

This app:
- ✅ Works completely offline
- ✅ Stores all data locally on your device
- ✅ Does not collect any personal information
- ✅ Does not use analytics or advertising
- ✅ Does not require account registration

See [Privacy Policy](PRIVACY_POLICY.md) for details.

## License

MIT License

## Contact

For questions or feedback:
- Email: don4444@duck.com

## Changelog

### v1.0.0 (2025-12-14)
- Initial release
- TXT and PDF file creation
- TXT + PDF pair management
- Basic file management (view, rename, delete)