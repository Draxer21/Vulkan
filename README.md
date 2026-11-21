# Drax Vulkan 0.2.0 (Minecraft 26.2 · NeoForge)

Mod solo de cliente que mejora el uso del backend **Vulkan** que trae Minecraft desde 26.2.

## Qué hace

| Área | Función |
|---|---|
| Diagnóstico | HUD con **F8**: backend activo, GPU, tipo (dedicada/integrada), driver, FPS, 1 % bajos, pico de frame, **uso real de VRAM** (VK_EXT_memory_budget) y gráfico de tiempos de frame |
| GPU | `/draxvulkan gpus` lista todas las GPU Vulkan. `/draxvulkan gpu <n> env` genera las variables del cargador Vulkan/Mesa para el launcher. `/draxvulkan gpu <n> windows` guarda la preferencia de GPU de Windows para tu Java |
| Estabilidad | **CrashGuard**: si el juego se cierra mal N veces seguidas con Vulkan (por defecto 2), deja OpenGL para el siguiente inicio y te avisa |
| Compatibilidad | Escanea los mods instalados buscando llamadas directas a OpenGL (no dibujan nada o crashean con Vulkan) y avisa al entrar al mundo. Lista de mods con problemas conocidos (Voxy, Distant Horizons antiguo, etc.) |
| Arreglo | Desactiva la pantalla de carga temprana de NeoForge en `fml.toml` cuando usas Vulkan (causa congelamientos al arrancar) |
| Rendimiento | **Modo de presentación** (mixin en `GpuSurface.configure`): SMART usa MAILBOX con vsync apagado (sin tearing) y FIFO_RELAXED con vsync encendido (sin caídas a 30 FPS). `/draxvulkan present <modo>` lo cambia en vivo |
| Rendimiento | **Precompilación de pipelines**: compila en segundo plano (máx. ~4 ms por tick) todos los `RenderPipelines` de vanilla para evitar tirones la primera vez que algo aparece; se repite tras recargar recursos |
| GPU | **Selección real de GPU** (mixin en `VulkanBackend.isDeviceSuitable`): `/draxvulkan gpu <n>` o `gpu auto`, se aplica al reiniciar |
| Otros | `/draxvulkan backend <vulkan|opengl|default>` · `/draxvulkan dump` |

Configuración: Mods → Drax Vulkan → Config (o `config/draxvulkan-client.toml`).

## Compilar

1. Copia `gradle/`, `gradlew` y `gradlew.bat` de tu proyecto FactoryCraft 26.2.
2. Ajusta en `gradle.properties` el valor `neo_version`, y en `build.gradle` la versión de `net.neoforged.moddev`, a los mismos que usas en FactoryCraft.
3. `./gradlew build` → `build/libs/DraxVulkan-0.1.0+mc26.2.jar`

O súbelo a un repositorio de GitHub: el workflow `.github/workflows/build.yml` lo compila y deja el .jar como artefacto.

## Notas
- `VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT` es una constante que el compilador incrusta en el código de Mojang: no se puede cambiar desde un mod sin reescribir esas clases.
- El escáner de compatibilidad separa los mods con OpenGL directo de los que solo tienen rutas OpenGL alternativas (detectan el backend). Sodium 0.9+ se considera compatible.
