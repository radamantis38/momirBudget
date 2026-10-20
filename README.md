# Mimir MTG (Momir Basic Printer)

Mimir MTG is an Android application designed to bring the "Momir Basic" format to the physical world, allowing you to search for creatures by their Converted Mana Cost (CMC) via the Scryfall API and print them instantly on portable thermal printers (Cat Printers).

## Features

*   **Momir Search:** Generates random creatures based on the entered CMC.
*   **Scryfall Integration:** Fetches high-quality images and official *Oracle Text*.
*   **Optimized Image Processing:**
    *   Monochrome conversion using **Floyd-Steinberg Dithering**.
    *   Focus filter (High-Pass) to ensure card text is legible on thermal paper.
    *   Aggressive Gamma correction for deep blacks.
*   **YHK Printing Protocol:**
    *   Low-level implementation of the BLE protocol for "Cat Printer" type devices.
    *   Master handshake (`1D 49 F0 19`) for motor unlocking on YHK hardware.
    *   Fluid raster printing with mechanical synchronization control (12ms/row).
    *   Automatic 6-line feed for easy manual cutting.

## Hardware Requirements

*   **Printer:** BLE thermal printers (WalkPrint, GB01, MX05, GT01 models, and YHK clones).
*   **Paper:** Standard 57mm thermal rolls.

## Technology Stack

*   **UI:** Jetpack Compose (Material 3).
*   **Networking:** Retrofit 2.9.0 & OkHttp.
*   **Images:** Coil 2.6.0.
*   **Bluetooth:** Android Bluetooth Low Energy (BLE) with coroutines and Mutex for buffer synchronization.
*   **Language:** Kotlin.

## Setup and Usage

1.  Turn on your printer and ensure it is paired via Bluetooth to your phone.
2.  Open Mimir MTG and tap **Select Printer** to choose your device.
3.  Enter the desired CMC and tap **Search & Print**.
4.  The app will download the card, process the dithering, and send it automatically to the printer.

## Installation

You can download the latest version of the application from the link below:

**[Download Mimir MTG v1.0 APK](app-release.apk)**

## Credits

*   **Card API:** [Scryfall](https://scryfall.com/docs/api)
*   **Protocol:** Based on reverse engineering of WalkPrint and ESC/POS protocols for YHK hardware.
