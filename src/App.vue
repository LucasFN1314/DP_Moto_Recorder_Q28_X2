<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { registerPlugin } from '@capacitor/core';
import { Filesystem } from '@capacitor/filesystem';
import { App } from '@capacitor/app';

const BluetoothAudio = registerPlugin('BluetoothAudio');
const LocalWifi = registerPlugin('LocalWifi');
const VideoPreview = registerPlugin('VideoPreview');
const KeepAlive = registerPlugin('KeepAlive');

const viewfinderRef = ref(null);

const isRecording = ref(false);
const isMuxing = ref(false);
const statusMessage = ref("CONECTANDO...");
const recordingTime = ref("00:00");
const micVolume = ref(1.0);
const batteryLevel = ref("100%");
const isPreviewActive = ref(true);
let batteryInterval = null;

const INTERCOM_SSID = "YX-Q28 2X-WIFI3a0578";

const INTERCOM_SSID_KEY = "intercom_ssid_pref";
const intercomSsid = ref(localStorage.getItem(INTERCOM_SSID_KEY) || "YX-Q28 2X-WIFI3a0578");

const showSettings = ref(false);
const availableNetworks = ref([]);
const isScanning = ref(false);

const updateBattery = async () => {
  try {
    const res = await BluetoothAudio.getBatteryLevel();
    if (res && res.level !== undefined && res.level > -1) {
      batteryLevel.value = `${res.level}%`;
    } else {
      batteryLevel.value = "---"; // Fallback
    }
  } catch (e) { }
};

const scanWifi = async () => {
  isScanning.value = true;
  try {
    const result = await LocalWifi.scanNetworks();
    // Filtrar duplicados y ordenar por señal
    const unique = [];
    const map = new Map();
    for (const net of result.networks) {
      if (!map.has(net.ssid)) {
        map.set(net.ssid, true);
        unique.push(net);
      }
    }
    availableNetworks.value = unique.sort((a, b) => b.level - a.level);
  } catch (e) {
    console.error("Error escaneando:", e);
  } finally {
    isScanning.value = false;
  }
};

const selectNetwork = (ssid) => {
  intercomSsid.value = ssid;
  saveSettings();
};

const saveSettings = () => {
  localStorage.setItem(INTERCOM_SSID_KEY, intercomSsid.value);
  showSettings.value = false;
  connectIntercomWifi(); // Re-conectar con el nuevo SSID
};

const connectIntercomWifi = async () => {
  try {
    statusMessage.value = "BUSCANDO INTERCOM...";
    const result = await LocalWifi.connect({ ssid: intercomSsid.value });
      if (result.success) {
        statusMessage.value = "LISTA PARA GRABAR";
        updateBattery();
        if (isPreviewActive.value) {
          setTimeout(() => {
            startVideoPreview();
          }, 3000);
        }
      }
  } catch (e) {
    console.error("Error conectando al WiFi:", e);
    statusMessage.value = "ERROR WIFI";
    // Reintentar en 5 segundos si falla y no estamos en settings
    if (!showSettings.value) {
      setTimeout(connectIntercomWifi, 5000);
    }
  }
};

let appStateListener = null;

onMounted(() => {
  document.body.style.backgroundColor = 'transparent';
  document.documentElement.style.backgroundColor = 'transparent';

  connectIntercomWifi();
  batteryInterval = setInterval(updateBattery, 30000); // Actualizar cada 30 segundos

  // Manejar el ciclo de vida de la app para restaurar la previsualización
  appStateListener = App.addListener('appStateChange', ({ isActive }) => {
    if (isActive && isPreviewActive.value && !isRecording.value) {
      // Pequeño retraso para asegurar que el sistema esté listo
      setTimeout(startVideoPreview, 1000);
    } else {
      stopVideoPreview();
    }
  });
});

let currentVideoPath = '';
let currentAudioPath = '';
let finalPath = '';
let isIntentionalStop = false;
let timerInterval = null;
let secondsElapsed = 0;

const rtspUrl = 'rtsp://192.168.25.1:8080/?action=stream';

const startVideoPreview = async () => {
  if (!viewfinderRef.value) return;
  const rect = viewfinderRef.value.getBoundingClientRect();
  try {
    await VideoPreview.start({
      url: rtspUrl,
      x: rect.left,
      y: rect.top,
      width: rect.width,
      height: rect.height
    });
  } catch (e) {
    console.error("Error starting video preview:", e);
  }
};

const stopVideoPreview = async () => {
  try {
    await VideoPreview.stop();
  } catch (e) {}
};

const togglePreview = async () => {
  isPreviewActive.value = !isPreviewActive.value;
  if (isPreviewActive.value) {
    await startVideoPreview();
  } else {
    await stopVideoPreview();
  }
};

const formatTime = (secs) => {
  const m = Math.floor(secs / 60).toString().padStart(2, '0');
  const s = (secs % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
};

const startTimer = () => {
  secondsElapsed = 0;
  recordingTime.value = "00:00";
  timerInterval = setInterval(() => {
    secondsElapsed++;
    recordingTime.value = formatTime(secondsElapsed);
  }, 1000);
};

const stopTimer = () => {
  if (timerInterval) clearInterval(timerInterval);
};

const toggleRecording = async () => {
  if (!window.ffmpeg) {
    statusMessage.value = "ERROR: FFMPEG NO DISPONIBLE";
    return;
  }

  if (isRecording.value) {
    isIntentionalStop = true;
    statusMessage.value = "DETENIENDO...";
    try {
      await BluetoothAudio.stopRecording();
    } catch (e) {
      console.error("Error al detener audio:", e);
    }

    window.ffmpeg.cancel(
      () => console.log("Se envió orden de detener a FFmpeg."),
      (err) => console.error("Error al detener FFmpeg:", err)
    );
    stopTimer();
    try { await KeepAlive.stop(); } catch (e) {}
    return;
  }

  isRecording.value = true;
  isIntentionalStop = false;
  statusMessage.value = "PREPARANDO...";

  // Detener la vista previa para evitar saturar el ancho de banda del intercomunicador (2 conexiones simultáneas saturan la red)
  if (isPreviewActive.value) {
    await stopVideoPreview();
  }

  const folderPath = '/storage/emulated/0/Download/Videos Digital Power Recorder';
  try {
    await Filesystem.mkdir({ path: folderPath, recursive: true });
  } catch (e) { }

  const timestamp = new Date().getTime();
  finalPath = `${folderPath}/moto_record_${timestamp}.mp4`;
  currentVideoPath = `/storage/emulated/0/Download/temp_vid_${timestamp}.ts`;
  currentAudioPath = `/storage/emulated/0/Download/temp_aud_${timestamp}.m4a`;

  try {
    await BluetoothAudio.startRecording({ outputPath: currentAudioPath });
  } catch (e) {
    console.error("No se pudo iniciar micrófono Bluetooth:", e);
  }

  const ffmpegCommand = `-y -fflags +genpts -i ${rtspUrl} -an -c:v copy ${currentVideoPath}`;

  statusMessage.value = "GRABANDO";
  startTimer();
  try { await KeepAlive.start(); } catch (e) {}

  window.ffmpeg.exec(
    ffmpegCommand,
    (success) => handleMuxing(),
    (failure) => {
      console.log(`RTSP finalizado o falló: ${failure}`);
      handleMuxing();
    }
  );
};

const cleanTempFiles = async () => {
  try { await Filesystem.deleteFile({ path: currentVideoPath }); } catch (e) { }
  try { await Filesystem.deleteFile({ path: currentAudioPath }); } catch (e) { }
};

const handleMuxing = () => {
  if (!isRecording.value) return;
  isRecording.value = false;
  stopTimer();
  try { KeepAlive.stop(); } catch (e) {}

  if (!isIntentionalStop) {
    statusMessage.value = "CONEXIÓN INTERRUMPIDA";
    cleanTempFiles();
    setTimeout(() => { 
      statusMessage.value = "LISTA PARA GRABAR"; 
      if (isPreviewActive.value) startVideoPreview();
    }, 3000);
    return;
  }

  isMuxing.value = true;
  statusMessage.value = "PROCESANDO VIDEO...";

  setTimeout(() => {
    // Aplicamos el filtro de volumen de audio al muxear
    const muxCommand = `-y -i ${currentVideoPath} -i ${currentAudioPath} -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -af volume=${micVolume.value} -ar 44100 -b:a 128k -shortest "${finalPath}"`;

    window.ffmpeg.exec(
      muxCommand,
      async (success) => {
        statusMessage.value = "¡VIDEO GUARDADO!";
        await cleanTempFiles();
        isMuxing.value = false;
        setTimeout(() => { 
          statusMessage.value = "LISTA PARA GRABAR"; 
          recordingTime.value = "00:00"; 
          if (isPreviewActive.value) startVideoPreview();
        }, 3000);
      },
      async (failure) => {
        statusMessage.value = "FALLO AL PROCESAR";
        isMuxing.value = false;
        setTimeout(() => { 
          statusMessage.value = "LISTA PARA GRABAR"; 
          if (isPreviewActive.value) startVideoPreview();
        }, 3000);
      }
    );
  }, 3000);
};

const openGallery = async () => {
  try {
    await BluetoothAudio.openOutputFolder();
  } catch (e) {
    console.error(e);
  }
};

onUnmounted(() => {
  if (appStateListener) {
    appStateListener.remove();
  }
  stopVideoPreview();
  stopTimer();
  if (batteryInterval) clearInterval(batteryInterval);
});
</script>

<template>
  <div class="camera-ui">

    <!-- Top Bar -->
    <div class="top-bar">
      <div class="resolution-badge">1080P / 60FPS</div>
      <div class="top-right">
        <button class="preview-toggle" @click="togglePreview" :class="{ 'inactive': !isPreviewActive }" title="Alternar Vista">
          <svg v-if="isPreviewActive" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
            <circle cx="12" cy="12" r="3" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
            <line x1="1" y1="1" x2="23" y2="23" />
          </svg>
        </button>
        <button class="settings-trigger" @click="showSettings = true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round"
              d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </button>
        <div class="battery-indicator">
          <span class="battery-text">{{ batteryLevel }}</span>
          <div class="battery-icon">
            <div class="battery-level"></div>
          </div>
        </div>
      </div>
    </div>

    <!-- Settings Modal -->
    <div v-if="showSettings" class="settings-overlay" @click.self="showSettings = false">
      <div class="settings-modal">
        <div class="modal-header">
          <h3>Configuración de Red</h3>
          <button class="close-btn" @click="showSettings = false">&times;</button>
        </div>

        <div class="modal-body">
          <div class="input-group">
            <label>SSID del Intercomunicador</label>
            <input type="text" v-model="intercomSsid" placeholder="Ej: YX-Q28..." class="manual-input" />
          </div>

          <div class="scan-section">
            <div class="scan-header">
              <label>Redes Disponibles</label>
              <button class="scan-btn" @click="scanWifi" :disabled="isScanning">
                {{ isScanning ? 'Escaneando...' : 'Buscar' }}
              </button>
            </div>

            <div class="networks-list">
              <div v-if="availableNetworks.length === 0" class="no-networks">
                {{ isScanning ? 'Buscando redes cercanas...' : 'No hay redes en la lista' }}
              </div>
              <button v-for="net in availableNetworks" :key="net.bssid" class="network-item" @click="selectNetwork(net.ssid)">
                <span class="net-ssid">{{ net.ssid }}</span>
                <span class="net-signal">{{ net.level }} dBm</span>
              </button>
            </div>
          </div>
        </div>

        <div class="modal-footer">
          <button class="save-btn" @click="saveSettings">Guardar y Conectar</button>
        </div>
      </div>
    </div>

    <!-- Center Display -->
    <div class="center-display">
      <div class="title-container">
        <h1 class="main-title">DIGITAL POWER</h1>
        <p class="sub-title">MOTO RECORDER</p>
      </div>

      <div class="viewfinder" ref="viewfinderRef">
        <!-- Previsualización en vivo mediante VideoView nativo -->

        <div class="crosshair ch-horizontal"></div>
        <div class="crosshair ch-vertical"></div>

        <div class="status-panel">
          <div v-if="isRecording" class="recording-indicator">
            <div class="red-dot pulse"></div>
            <span class="timer-text">{{ recordingTime }}</span>
          </div>
          <div v-else class="standby-indicator">
            <div class="status-text">{{ statusMessage }}</div>
          </div>

          <div v-if="isMuxing" class="muxing-indicator pulse">
            PROCESANDO...
          </div>
        </div>
      </div>
    </div>

    <!-- Audio Settings -->
    <div class="audio-control" :class="{ 'disabled': isRecording || isMuxing }">
      <div class="audio-header">
        <svg class="mic-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round"
            d="M12 1v11m0 0a4 4 0 01-4-4V5a4 4 0 018 0v3a4 4 0 01-4 4z"></path>
          <path stroke-linecap="round" stroke-linejoin="round" d="M19 10v2a7 7 0 01-14 0v-2m7 9v3m-3 0h6"></path>
        </svg>
        <span>Volumen del Micrófono: {{ Math.round(micVolume * 100) }}%</span>
      </div>
      <input type="range" min="0.1" max="3.0" step="0.1" v-model="micVolume" :disabled="isRecording || isMuxing"
        class="volume-slider" />
    </div>

    <!-- Bottom Controls -->
    <div class="bottom-controls">
      <button class="shutter-button" @click="toggleRecording" :disabled="isMuxing">
        <div class="shutter-ring">
          <div class="shutter-inner" :class="{ 'is-recording': isRecording }"></div>
        </div>
      </button>

      <!-- Gallery Button -->
      <button class="gallery-button" @click="openGallery" title="Abrir Carpeta">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round"
            d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"></path>
        </svg>
      </button>
    </div>

  </div>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Share+Tech+Mono&display=swap');

* {
  box-sizing: border-box;
}

.camera-ui {
  position: relative;
  width: 100%;
  height: 100vh;
  background-color: transparent;
  color: #fff;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  user-select: none;
}

.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px 20px;
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.9), transparent);
  z-index: 10;
}

.top-right {
  display: flex;
  align-items: center;
  gap: 15px;
}

.settings-trigger {
  background: none;
  border: none;
  color: #00d2ff;
  padding: 5px;
  cursor: pointer;
  display: flex;
  align-items: center;
}

.preview-toggle {
  background: none;
  border: none;
  color: #00d2ff;
  padding: 5px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: all 0.3s ease;
}

.preview-toggle.inactive {
  color: #666;
}

.settings-trigger svg,
.preview-toggle svg {
  width: 24px;
  height: 24px;
}

.resolution-badge {
  background: rgba(255, 255, 255, 0.15);
  padding: 5px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1px;
}

.battery-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
}

.battery-text {
  font-size: 12px;
  font-weight: bold;
}

.battery-icon {
  width: 24px;
  height: 12px;
  border: 1px solid #fff;
  border-radius: 2px;
  position: relative;
  padding: 1px;
}

.battery-icon::after {
  content: '';
  position: absolute;
  right: -3px;
  top: 2px;
  width: 2px;
  height: 6px;
  background: #fff;
  border-radius: 0 2px 2px 0;
}

.battery-level {
  width: 100%;
  height: 100%;
  background: #fff;
}

/* Settings Modal Styles */
.settings-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.85);
  backdrop-filter: blur(10px);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.settings-modal {
  background: #1a1a1a;
  width: 100%;
  max-width: 400px;
  border-radius: 24px;
  border: 1px solid rgba(0, 210, 255, 0.3);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
}

.modal-header {
  padding: 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.modal-header h3 {
  margin: 0;
  font-size: 18px;
  color: #00d2ff;
}

.close-btn {
  background: none;
  border: none;
  color: #fff;
  font-size: 28px;
  line-height: 1;
  cursor: pointer;
}

.modal-body {
  padding: 20px;
  flex: 1;
  overflow-y: auto;
}

.input-group {
  margin-bottom: 25px;
}

.input-group label {
  display: block;
  font-size: 12px;
  color: #aaa;
  margin-bottom: 8px;
  text-transform: uppercase;
}

.manual-input {
  width: 100%;
  background: #222;
  border: 1px solid #333;
  padding: 12px 15px;
  border-radius: 12px;
  color: #fff;
  font-size: 16px;
  outline: none;
}

.manual-input:focus {
  border-color: #00d2ff;
}

.scan-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.scan-header label {
  font-size: 12px;
  color: #aaa;
  text-transform: uppercase;
}

.scan-btn {
  background: rgba(0, 210, 255, 0.1);
  border: 1px solid #00d2ff;
  color: #00d2ff;
  padding: 5px 15px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: bold;
}

.networks-list {
  background: #111;
  border-radius: 12px;
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid #222;
}

.no-networks {
  padding: 20px;
  text-align: center;
  color: #666;
  font-size: 14px;
}

.network-item {
  width: 100%;
  display: flex;
  justify-content: space-between;
  padding: 15px;
  background: none;
  border: none;
  border-bottom: 1px solid #222;
  color: #fff;
  text-align: left;
}

.network-item:last-child {
  border-bottom: none;
}

.network-item:active {
  background: rgba(0, 210, 255, 0.1);
}

.net-ssid {
  font-weight: 500;
}

.net-signal {
  color: #666;
  font-size: 12px;
}

.modal-footer {
  padding: 20px;
}

.save-btn {
  width: 100%;
  background: #00d2ff;
  border: none;
  color: #000;
  padding: 15px;
  border-radius: 15px;
  font-weight: bold;
  font-size: 16px;
}

.center-display {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  width: 100%;
}

.title-container {
  position: absolute;
  top: 20px;
  text-align: center;
  z-index: 5;
}

.main-title {
  margin: 0;
  font-size: 24px;
  font-weight: 900;
  letter-spacing: 2px;
  color: #00d2ff;
  text-shadow: 0 0 10px rgba(0, 210, 255, 0.5);
}

.sub-title {
  margin: 0;
  font-size: 12px;
  letter-spacing: 4px;
  color: #aaa;
}

.viewfinder {
  position: relative;
  width: 90%;
  height: 60%;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  box-shadow: 0 0 0 4000px #050505;
  overflow: hidden;
}

.preview-stream {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.6;
  z-index: 1;
  pointer-events: none;
}

.crosshair {
  position: absolute;
  background: rgba(255, 255, 255, 0.15);
  z-index: 5;
}

.ch-horizontal {
  width: 100%;
  height: 1px;
  top: 50%;
  left: 0;
}

.ch-vertical {
  height: 100%;
  width: 1px;
  left: 50%;
  top: 0;
}

.status-panel {
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}

.recording-indicator {
  display: flex;
  align-items: center;
  gap: 15px;
  background: rgba(0, 0, 0, 0.6);
  padding: 10px 25px;
  border-radius: 50px;
  border: 1px solid rgba(255, 0, 0, 0.3);
}

.red-dot {
  width: 16px;
  height: 16px;
  background: #ff3333;
  border-radius: 50%;
  box-shadow: 0 0 15px rgba(255, 51, 51, 0.8);
}

.pulse {
  animation: pulseAnim 1.5s infinite;
}

@keyframes pulseAnim {
  0% {
    opacity: 1;
  }

  50% {
    opacity: 0.4;
  }

  100% {
    opacity: 1;
  }
}

.timer-text {
  font-family: 'Share Tech Mono', monospace;
  font-size: 48px;
  font-weight: 400;
  letter-spacing: -2px;
}

.standby-indicator {
  background: rgba(0, 0, 0, 0.6);
  padding: 15px 30px;
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  text-align: center;
}

.status-text {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 2px;
  color: #ddd;
}

.muxing-indicator {
  background: rgba(255, 193, 7, 0.2);
  color: #ffc107;
  padding: 8px 20px;
  border-radius: 50px;
  border: 1px solid rgba(255, 193, 7, 0.5);
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 1px;
}

.audio-control {
  padding: 0 30px;
  margin-bottom: 20px;
  position: relative;
  z-index: 10;
  transition: opacity 0.3s;
}

.audio-control.disabled {
  opacity: 0.3;
  pointer-events: none;
}

.audio-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #ccc;
  margin-bottom: 10px;
}

.mic-icon {
  width: 18px;
  height: 18px;
}

.volume-slider {
  width: 100%;
  -webkit-appearance: none;
  height: 4px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 2px;
  outline: none;
}

.volume-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #00d2ff;
  cursor: pointer;
  box-shadow: 0 0 10px rgba(0, 210, 255, 0.5);
}

.bottom-controls {
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  z-index: 10;
  background: linear-gradient(to top, rgba(0, 0, 0, 1), transparent);
  padding-bottom: 20px;
}

.shutter-button {
  background: none;
  border: none;
  padding: 0;
  cursor: pointer;
  outline: none;
}

.shutter-button:disabled {
  opacity: 0.5;
}

.shutter-ring {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  border: 4px solid #444;
  background: #222;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.8);
  transition: transform 0.1s;
}

.shutter-button:active .shutter-ring {
  transform: scale(0.95);
}

.shutter-inner {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #ff3333;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.shutter-inner.is-recording {
  border-radius: 10px;
  transform: scale(0.6);
}

.gallery-button {
  position: absolute;
  right: 40px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 12px;
  width: 50px;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  cursor: pointer;
  transition: all 0.2s;
  outline: none;
}

.gallery-button:active {
  transform: scale(0.9);
  background: rgba(255, 255, 255, 0.2);
}

.gallery-button svg {
  width: 24px;
  height: 24px;
}
</style>

<style>
body,
html {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  background-color: transparent;
  overflow: hidden;
  overscroll-behavior: none;
}

#app {
  width: 100%;
  height: 100%;
}
</style>