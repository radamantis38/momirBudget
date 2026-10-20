# Mimir MTG (Momir Basic Printer)

Mimir MTG es una aplicación de Android diseñada para llevar el formato "Momir Basic" al mundo físico, permitiendo buscar criaturas por su Valor de Maná Convertido (CMC) a través de la API de Scryfall e imprimirlas instantáneamente en impresoras térmicas portátiles (Cat Printers).

## Características

*   **Búsqueda Momir:** Genera criaturas aleatorias basadas en el CMC ingresado.
*   **Integración con Scryfall:** Obtiene imágenes de alta calidad y el *Oracle Text* oficial.
*   **Procesamiento de Imagen Optimizado:**
    *   Conversión a monocromo mediante **Dithering de Floyd-Steinberg**.
    *   Filtro de enfoque (High-Pass) para asegurar que el texto de las cartas sea legible en papel térmico.
    *   Corrección Gamma agresiva para negros profundos.
*   **Protocolo de Impresión YHK:**
    *   Implementación de bajo nivel del protocolo BLE para impresoras tipo "Cat Printer".
    *   Handshake maestro (`1D 49 F0 19`) para desbloqueo de motor en hardware YHK.
    *   Impresión raster fluida con control de sincronización mecánica (16ms/fila).

## Requisitos de Hardware

*   **Impresora:** Impresoras térmicas BLE (modelos WalkPrint, GB01, MX05, GT01 y clones YHK).
*   **Papel:** Rollos térmicos estándar de 57mm.

## Stack Tecnológico

*   **UI:** Jetpack Compose (Material 3).
*   **Networking:** Retrofit 2.9.0 & OkHttp.
*   **Imágenes:** Coil 2.6.0.
*   **Bluetooth:** Android Bluetooth Low Energy (BLE) con corrutinas y Mutex para sincronización de buffers.
*   **Lenguaje:** Kotlin 2.2.10.

## Configuración y Uso

1.  Asegúrate de que tu impresora esté encendida y vinculada por Bluetooth a tu teléfono.
2.  Abre Mimir MTG y pulsa en **Configurar Impresora** para seleccionar tu dispositivo.
3.  Ingresa el CMC deseado y pulsa **Buscar e Imprimir**.
4.  La aplicación descargará la carta, procesará el dithering y la enviará automáticamente a la impresora.

## Créditos

*   **API de Cartas:** [Scryfall](https://scryfall.com/docs/api)
*   **Protocolo:** Basado en ingeniería inversa de protocolos WalkPrint y ESC/POS para hardware YHK.
