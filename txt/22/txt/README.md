# TXT App

A simple, offline text editor for Android that saves documents as TXT and PDF files.

## Features

- **Create & Edit TXT files** - Simple text editing with auto-save to the Downloads folder
- **Export to PDF** - Convert your text documents to PDF format with automatic pagination
- **TXT + PDF Pair Management** - Save both formats together; edit TXT and update the paired PDF automatically
- **PDF Viewer: Text Copy** - Long-press and drag to select a region on the PDF page and copy the matched text via a context menu
- **PDF Viewer: Full Page Copy** - Copy the full text of the current page from the PDF viewer
- **File Manager Integration + Sync** - Open TXT/PDF from a file manager and keep the in-app document list synced and refreshed
- **Offline Operation** - No internet connection required; all data stays on your device
- **File Management** - View, rename, and delete your documents from within the app

## Screenshots

*(Add screenshots here)*

## Requirements

- Android 8.0 (API 26) or higher
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
    - **Save TXT only** - Creates an editable `.txt` file
    - **Save TXT + PDF** - Creates both `.txt` and `.pdf` files
    - **Export PDF only** - Creates a new `.pdf` file

### Editing Existing Documents
- **TXT files**: Tap to open in editor
- **PDF files**: Tap to edit linked TXT (if it exists), or tap the view icon to open the PDF reader

### Copying Text from PDF
1. Open a PDF in the PDF reader
2. **Long-press and drag** to draw a selection region over the text on the page
3. Release to show the context menu, then tap:
   - **Copy** (copies only the selected region)
   - **Copy page** (copies the full text of the current page)

> Note: If the PDF is a scanned image without an embedded text layer, there may be no selectable text to copy.

### Renaming Files
Tap the filename in the editor's top bar to rename

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: Single Activity with Compose Navigation
- **Storage**: MediaStore API (Scoped Storage)
- **PDF Generation**: Android PdfDocument API
- **PDF Viewing**: Android PdfRenderer + PDFBox (text extraction for copy)

## Project Structure

```
app/src/main/java/com/krdondon/txt/
├── MainActivity.kt                # Main entry point & navigation + file manager open handling
├── model/
│   └── FileItem.kt                # Data class for file information
├── pdf/
│   └── PdfTextLayoutExtractor.kt  # Extracts positioned text to support region selection + copy
├── ui/theme/
│   ├── screens/
│   │   ├── EditorScreen.kt        # Text editing screen
│   │   ├── FileListScreen.kt      # Document list screen
│   │   └── PdfViewerScreen.kt     # PDF viewer + selection/copy UX
│   ├── Color.kt
│   ├── Theme.kt
│   └── Type.kt
└── utils/
    ├── FileManager.kt             # File I/O operations
    ├── PdfExporter.kt             # PDF generation
    └── PermissionHandler.kt       # Permission utilities
```

## Building from Source

1. Clone the repository
```bash
# (add your repo URL here)
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

### v1.0.1 (2026-01-12)
- Added PDF viewer text copy:
  - Region selection (long-press + drag) and copy via context menu
  - Full-page text copy option
- Improved PDF viewer page fit/visibility
- Improved file manager open flow and in-app list refresh/sync

### v1.0.0 (2025-12-14)
- Initial release
- TXT and PDF file creation
- TXT + PDF pair management
- Basic file management (view, rename, delete)
