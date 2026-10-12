# Directrices de Desarrollo (AGENTS.md)

Reglas de rendimiento y estabilidad para este repositorio:

## 1. Rendimiento y Memoria
- **Cero instanciaciones en rutas calientes**: No crees objetos temporales (`StringBuilder`, `ArrayList`, `HashSet`, `Paint`, `String`) en `MotionEvent.ACTION_MOVE`, `onDraw`, sugerencias o pulsación de teclas. Reutiliza buffers de instancia (`mScratch*`, `ThreadLocal`) con `.clear()`.
- **Cero singletons en rutas calientes**: No llames a `.getInstance()` dentro de `onDraw`, `onTouchEvent`, handlers de swipe o pulsación de tecla. Guarda la referencia como campo de instancia. Inicialízala en `onCreate`, `setKeyboard` o al inicio del gesto.
- **Configuración inmutable**: Mantén `SettingsValues` inmutable (`public final`). Esto permite lecturas concurrentes sin locks ni acceso a disco.
- **Colecciones primitivas**: Evita el boxing (`Integer`, `Float`). Usa `SparseArray`, `SparseIntArray` o arrays planos.

## 2. Higiene de I/O y Estado
- **Cero escrituras redundantes**: Antes de llamar a `SharedPreferences.apply()` o escribir en disco, verifica que el valor cambió (`oldValue != newValue`, o revisa el retorno de `set.add()`). Cada `apply()` dispara listeners globales y causa cascadas de recargas.
- **Cero fallos silenciosos**: No uses `if (x == null) return;` en silencio. Si falta un estado crítico, registra el error con `Log.w(TAG, "...")` o `Log.e(TAG, "...")` antes de salir. Esto hace visibles los bugs de ciclo de vida en debug. R8 limpia los logs en release.

## 3. Disciplina de Código
- **Corrige el origen**: Si un componente falla por falta de estado, no parches los consumidores finales con llamadas cruzadas. Busca dónde nace la entidad e inicialízala allí.
- **Respeta el motor nativo**: Este proyecto deriva de AOSP LatinIME. No refactorices pipelines centrales (decodificadores, parsers, renderers) a menos que el ticket lo exija.
- **Usa los parámetros**: Si un método recibe `Context` o `SharedPreferences`, úsalos. No ignores parámetros para consultar singletons globales.
- **Itera directo**: No uses `for (int i = 0; i < MAX; i++)` con colecciones dinámicas o dispersas. Itera sobre los elementos activos.
- **Usa tags reales**: Registra logs con el `TAG` de la clase o `NombreClase.class.getSimpleName()`. No inventes tags como `"SlopSweep"` o `"AIFix"`.
- **Cero código muerto y redundancia**: Revisa la implementación actual y el código nuevo antes de hacer commit o PR. Busca métodos sin invocar, constantes huérfanas, ramas inalcanzables y lógica repetida. Extrae bloques comunes y elimina restos no utilizados.
- **Cambios atómicos**: Un PR (o un commit directo a `master`, si tienes acceso de push) por bug o característica con el diff mínimo. Separa los cambios cosméticos de las correcciones funcionales. El acceso de push no requiere revisión previa: los colaboradores de confianza pueden commitear directamente.

## 4. Compatibilidad y Pruebas
- **Soporte para `TYPE_NULL`**: Si el editor reporta `InputType.TYPE_NULL`, despacha los caracteres mediante eventos físicos (`sendKeyChar` o `sendDownUpKeyEvent`).
- **Verificación**: Compila el código y asegúrate de pasar todos los tests:
  `./gradlew testDebugUnitTest assembleDebug assembleRelease`

## 5. Archivos Bloqueados (Core Neuronal C++ & JNI)
**ESTRICTAMENTE PROHIBIDO:** Ningún contribuidor, agente de IA o desarrollador de UI debe modificar los siguientes archivos sin la aprobación explícita del Arquitecto Principal. Estos archivos contienen la lógica matemática de Inferencia Ternaria (1.58-bit BitNet), paridad de tokenización BPE UTF-8, optimizaciones Cero-Alocación (zero-allocation), vectorización SIMD (ARM NEON / SSE) y la integración de memoria segura nativa. Cualquier modificación irresponsable aquí causará crashes de VRAM, Memory Leaks, desbordamientos de buffer, ANRs, o romperá la paridad de la Loss con el entrenamiento en PyTorch.

- `app/src/main/cpp/micro_transformer.h` *(Definición del Motor C++, Trie BPE y Scratch Buffers Zero-Alloc)*
- `app/src/main/cpp/micro_transformer.cpp` *(Motor de Inferencia C++, GEMV Ternario 4-Row Blocked, SIMD NEON/SSE, Fast Top-K)*
- `app/src/main/cpp/micro_transformer_jni.cpp` *(Puente JNI Nativo y validación estricta de límites de memoria)*
- `app/src/main/cpp/CMakeLists.txt` *(Configuración de compilación NDK -O3 y fast-math)*
- `app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dict/neural/MicroTransformerModel.java` *(Conector JNI Java directo)*
- `app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dict/PrefixDictionary.java` *(Integración del Diccionario con Neural y Rescoring Z-score)*
- `app/src/test/java/rkr/simplekeyboard/inputmethod/latin/dict/neural/MicroTransformerModelTest.java` *(Golden Tests de paridad JNI/Nativo)*

Para el repositorio de entrenamiento (`simple-keyboard-neural`):
- `transformer_neural.ipynb` *(Notebook con la destilación Gemma 26B, Focal Loss y Ruido QWERTY)*


## 6. Idioma y localización modular (i18n)

- **Idioma del repositorio**: inglés estricto. Todo el código, identificadores, comentarios, commits (Conventional Commits), títulos y descripciones de PRs, issues y documentación (`README.md`, `CONTRIBUTING.md`) se escribe exclusivamente en inglés.
- **Texto de UI en recursos, cero hardcodeo**: ningún texto visible vive en layouts, diálogos o pantallas de ajustes. Todo texto de usuario reside en recursos `string`/`string-array`.
- **Base y fuente única de verdad**: `app/src/main/res/values/strings.xml` (inglés). El locale base se declara en `app/src/main/res/resources.properties` (`unqualifiedResLocale=en`).
- **Módulos de recursos**: `values/strings.xml` es el único módulo traducible. `values/strings-action-keys.xml` y `values/strings-appname.xml` son íntegramente `translatable="false"` (etiquetas internas, nombres y URLs) y nunca se copian a un `values-<locale>/`.
- **Contribuir un idioma**: crear `app/src/main/res/values-<locale>/strings.xml` espejando las claves del inglés base, manteniendo intactos los placeholders `<xliff:g>` y los argumentos de formato (`%1$s`, `%d`). Las entradas `translatable="false"` se omiten: resuelven por fallback al inglés automáticamente.
- **Build sin fricción**: `app/build.gradle` deriva el conjunto empaquetado (`resourceConfigurations`) del base más cada directorio `values-<locale>/` que contenga `strings.xml`. Las traducciones de librerías para otros locales se descartan. Agregar un directorio de traducción es el único paso necesario; ningún cambio de build hace falta.
