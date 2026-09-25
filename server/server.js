const http = require('http');
const express = require('express');
const { WebSocketServer, WebSocket } = require('ws');
const path = require('path');
const os = require('os');
const url = require('url');

// ---- AI Agent: Orchestrator (Groq STT + LLaMA tool calling) ----
const { processVoiceAndScreen, transcribeAudio } = require('./orchestrator');

// Warn at startup if API key is missing
if (!process.env.GROQ_API_KEY) {
  console.warn('[Server] WARNING: GROQ_API_KEY is not set. AI agent features will not work.');
}

// ---- AI Agent: Convert raw PCM buffer to WAV (prepend 44-byte RIFF header) ----
function pcmToWav(pcmBuffer, sampleRate = 44100, channels = 1, bitsPerSample = 16) {
  const byteRate = sampleRate * channels * (bitsPerSample / 8);
  const blockAlign = channels * (bitsPerSample / 8);
  const dataSize = pcmBuffer.length;
  const header = Buffer.alloc(44);

  header.write('RIFF', 0);
  header.writeUInt32LE(36 + dataSize, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);          // PCM chunk size
  header.writeUInt16LE(1, 20);           // PCM format
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(byteRate, 28);
  header.writeUInt16LE(blockAlign, 32);
  header.writeUInt16LE(bitsPerSample, 34);
  header.write('data', 36);
  header.writeUInt32LE(dataSize, 40);

  return Buffer.concat([header, pcmBuffer]);
}

// ---- AI Agent: Parse HMA1 binary audio packet ----
// Returns { type: 'config'|'data', ptsUs: Number, payload: Buffer } or null
function parseHma1Packet(buffer) {
  if (buffer.length < 17) return null;
  if (buffer[0] !== 0x48 || buffer[1] !== 0x4D || buffer[2] !== 0x41 || buffer[3] !== 0x31) return null;
  const type = buffer[4] === 0 ? 'config' : 'data';
  const ptsUs = buffer.readBigInt64BE(5);
  const payloadLen = buffer.readUInt32BE(13);
  if (buffer.length < 17 + payloadLen) return null;
  const payload = buffer.slice(17, 17 + payloadLen);
  return { type, ptsUs: Number(ptsUs), payload };
}

// ---- AI Agent: Send a JSON message to a device WebSocket safely ----
function sendToDevice(deviceRecord, payload) {
  try {
    if (deviceRecord?.ws?.readyState === WebSocket.OPEN) {
      deviceRecord.ws.send(JSON.stringify(payload));
    }
  } catch (e) {
    console.error('[Agent] sendToDevice error:', e.message);
  }
}

// ---- AI Agent: Full pipeline — STT → Screenshot → LLM → Command → TTS ----
async function runAgentPipeline(deviceRecord) {
  const { id: deviceId, username } = deviceRecord;

  deviceRecord.isCollectingVoice = false;
  sendToDevice(deviceRecord, { type: 'AGENT_STATUS', status: 'THINKING', message: 'Processing your request...' });

  try {
    // 1. Concatenate buffered PCM chunks and wrap as WAV
    const pcmBuffer = Buffer.concat(deviceRecord.audioChunks || []);
    deviceRecord.audioChunks = [];

    if (pcmBuffer.length < 1024) {
      console.log(`[Agent][${deviceId}] Audio too short — skipping STT`);
      sendToDevice(deviceRecord, { type: 'TTS_TEXT', text: "I didn't catch that. Please try again." });
      sendToDevice(deviceRecord, { type: 'AGENT_STATUS', status: 'IDLE', message: '' });
      return;
    }

    const wavBuffer = pcmToWav(pcmBuffer);

    // 2. Transcribe via Groq Whisper
    console.log(`[Agent][${deviceId}] Sending ${wavBuffer.length} bytes to Whisper STT...`);
    const transcript = await transcribeAudio(wavBuffer);

    if (!transcript || transcript.trim().length === 0) {
      console.log(`[Agent][${deviceId}] Empty transcript — skipping`);
      sendToDevice(deviceRecord, { type: 'TTS_TEXT', text: "I couldn't understand that. Could you say it again?" });
      sendToDevice(deviceRecord, { type: 'AGENT_STATUS', status: 'IDLE', message: '' });
      return;
    }

    console.log(`[Agent][${deviceId}] Transcript: "${transcript}"`);

    // 3. Request a screenshot from the device (5-second timeout)
    let screenshotBase64 = null;
    sendToDevice(deviceRecord, { type: 'SCREENSHOT_REQUEST' });

    screenshotBase64 = await new Promise((resolve) => {
      deviceRecord.isPendingScreenshot = true;
      deviceRecord.screenshotResolve = resolve;
      setTimeout(() => {
        if (deviceRecord.isPendingScreenshot) {
          deviceRecord.isPendingScreenshot = false;
          deviceRecord.screenshotResolve = null;
          console.log(`[Agent][${deviceId}] Screenshot timed out — proceeding without it`);
          resolve(null);
        }
      }, 5000);
    });

    // 4. Call AI Orchestrator
    const { action, parameters, thought } = await processVoiceAndScreen({
      username: username || 'User',
      transcript,
      screenshotBase64,
      conversationHistory: deviceRecord.conversationHistory || [],
    });

    console.log(`[Agent][${deviceId}] AI action: ${action}`, parameters);

    // 5. Update conversation history (rolling window of 10)
    deviceRecord.conversationHistory = deviceRecord.conversationHistory || [];
    deviceRecord.conversationHistory.push({ role: 'user', content: transcript });
    deviceRecord.conversationHistory.push({ role: 'assistant', content: thought || action });
    if (deviceRecord.conversationHistory.length > 20) {
      deviceRecord.conversationHistory = deviceRecord.conversationHistory.slice(-20);
    }

    // 6. Dispatch result to device
    if (action === 'speak_response') {
      const text = parameters?.text || thought || 'Done.';
      sendToDevice(deviceRecord, { type: 'TTS_TEXT', text });
      sendToDevice(deviceRecord, { type: 'AGENT_STATUS', status: 'IDLE', message: '' });
    } else {
      sendToDevice(deviceRecord, {
        type: 'COMMAND',
        thought: thought || '',
        action,
        parameters: parameters || {},
      });
      sendToDevice(deviceRecord, { type: 'TTS_TEXT', text: 'Got it, executing that now.' });
      sendToDevice(deviceRecord, { type: 'AGENT_STATUS', status: 'EXECUTING', message: action });
    }

  } catch (err) {
    console.error(`[Agent][${deviceId}] Pipeline error:`, err.message);
    sendToDevice(deviceRecord, { type: 'TTS_TEXT', text: 'Something went wrong. Please try again.' });
    sendToDevice(deviceRecord, { type: 'AGENT_STATUS', status: 'IDLE', message: '' });
  }
}


const app = express();
const PORT = process.env.PORT || 3000;

// Trust reverse proxies (Render, Cloudflare, Nginx)
app.set('trust proxy', 1);

// Enable CORS for API endpoints
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept');
  res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  if (req.method === 'OPTIONS') {
    return res.sendStatus(200);
  }
  next();
});

// Serve dashboard frontend
app.use(express.static(path.join(__dirname, 'public')));
app.use(express.json());

const server = http.createServer(app);

// WebSocket servers
const wssDevice = new WebSocketServer({ noServer: true });
const wssViewer = new WebSocketServer({ noServer: true });

// Multi-device registry
// Map<deviceId, { id, name, ws, width, height, fps, bitrate, connectedAt, cachedConfig, stats } >
const activeDevices = new Map();

// Helper to inspect NAL unit types in Annex B H.264
function extractSpsPps(buffer) {
  let hasSps = false;
  let hasPps = false;
  const len = buffer.length;
  for (let i = 0; i < len - 4; i++) {
    if (buffer[i] === 0 && buffer[i + 1] === 0) {
      let nalStart = -1;
      if (buffer[i + 2] === 1) {
        nalStart = i + 3;
      } else if (buffer[i + 2] === 0 && buffer[i + 3] === 1) {
        nalStart = i + 4;
      }
      if (nalStart !== -1 && nalStart < len) {
        const nalType = buffer[nalStart] & 0x1F;
        if (nalType === 7) hasSps = true;
        if (nalType === 8) hasPps = true;
      }
    }
  }
  return hasSps || hasPps;
}

function isKeyFrameOrConfig(buffer) {
  const len = buffer.length;
  for (let i = 0; i < Math.min(len - 4, 64); i++) {
    if (buffer[i] === 0 && buffer[i + 1] === 0) {
      let nalStart = -1;
      if (buffer[i + 2] === 1) {
        nalStart = i + 3;
      } else if (buffer[i + 2] === 0 && buffer[i + 3] === 1) {
        nalStart = i + 4;
      }
      if (nalStart !== -1 && nalStart < len) {
        const nalType = buffer[nalStart] & 0x1F;
        if (nalType === 5 || nalType === 7 || nalType === 8) return true;
      }
    }
  }
  return false;
}

// Helper to inspect HoneyMo Audio (HMA1) packets
function isAudioPacket(buffer) {
  return buffer.length >= 17 &&
    buffer[0] === 0x48 && // 'H'
    buffer[1] === 0x4D && // 'M'
    buffer[2] === 0x41 && // 'A'
    buffer[3] === 0x31;   // '1'
}

// Helper to determine the target device for a viewer (explicit subscription or first available)
function getViewerTargetDeviceId(viewer) {
  if (viewer.subscribedDeviceId && activeDevices.has(viewer.subscribedDeviceId)) {
    return viewer.subscribedDeviceId;
  }
  return activeDevices.keys().next().value || null;
}

function getDeviceList() {
  const list = [];
  for (const [id, dev] of activeDevices.entries()) {
    list.push({
      id: dev.id,
      name: dev.name,
      width: dev.width,
      height: dev.height,
      fps: dev.fps,
      bitrate: dev.bitrate,
      cameraAllowed: dev.cameraAllowed || false,
      cameraFacing: dev.cameraFacing || 'front',
      cameraActive: dev.cameraActive || false,
      connectedAt: dev.connectedAt,
      stats: dev.stats
    });
  }
  return list;
}

function broadcastDeviceListToViewers() {
  const payload = JSON.stringify({
    type: 'DEVICE_LIST',
    devices: getDeviceList()
  });

  wssViewer.clients.forEach((client) => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(payload);
    }
  });
}

// --------------------------------------------------------------------------
// WebSocket Heartbeat / Ping-Pong (Keeps Render proxy connection alive & low latency)
// --------------------------------------------------------------------------
const heartbeatInterval = setInterval(() => {
  wssDevice.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
  wssViewer.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 15000); // 15s interval for reliable proxy keep-alive

function setupHeartbeat(ws) {
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });
  ws.on('ping', () => {
    ws.isAlive = true;
  });
}

// --------------------------------------------------------------------------
// Device WebSocket Handler
// --------------------------------------------------------------------------
wssDevice.on('connection', (ws, req) => {
  setupHeartbeat(ws);

  const parsedUrl = url.parse(req.url, true);
  let deviceId = parsedUrl.query.id || `dev_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  let deviceName = parsedUrl.query.name || 'Android Device';

  const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
  console.log(`[Device] Connection initiated: ${deviceId} (${deviceName}) from ${clientIp}`);

  const deviceRecord = {
    id: deviceId,
    name: deviceName,
    ws: ws,
    width: 720,
    height: 1280,
    fps: 30,
    bitrate: 2000000,
    cameraAllowed: false,
    cameraFacing: 'front',
    cameraActive: false,
    connectedAt: new Date().toISOString(),
    cachedConfig: null,
    cachedAudioConfig: null,
    stats: {
      framesReceived: 0,
      bytesReceived: 0,
      currentFps: 0,
      currentBitrateKbps: 0
    },
    _secondFrames: 0,
    _secondBytes: 0,
    // ---- AI Agent state ----
    username: 'User',
    conversationHistory: [],
    audioChunks: [],
    isCollectingVoice: false,
    isPendingScreenshot: false,
    screenshotResolve: null,
  };

  activeDevices.set(deviceId, deviceRecord);
  broadcastDeviceListToViewers();

  // Calculate per-device FPS / Bitrate and send STATS to viewers
  const statsInterval = setInterval(() => {
    deviceRecord.stats.currentFps = deviceRecord._secondFrames;
    deviceRecord.stats.currentBitrateKbps = Math.round((deviceRecord._secondBytes * 8) / 1024);
    deviceRecord._secondFrames = 0;
    deviceRecord._secondBytes = 0;

    const statsPayload = JSON.stringify({
      type: 'STATS',
      deviceId: deviceId,
      stats: {
        fps: deviceRecord.stats.currentFps,
        bitrateKbps: deviceRecord.stats.currentBitrateKbps,
        totalFrames: deviceRecord.stats.framesReceived,
        totalBytes: deviceRecord.stats.bytesReceived
      }
    });

    wssViewer.clients.forEach((viewer) => {
      if (viewer.readyState === WebSocket.OPEN) {
        const targetDevId = getViewerTargetDeviceId(viewer);
        if (targetDevId === deviceId) {
          viewer.send(statsPayload);
        }
      }
    });
  }, 1000);

  ws.on('message', (message, isBinary) => {
    if (isBinary) {
      // Check if this is a HoneyMo Audio (HMA1) packet
      if (isAudioPacket(message)) {
        const isAudioConfig = message.length >= 5 && message[4] === 0;
        if (isAudioConfig) {
          deviceRecord.cachedAudioConfig = Buffer.from(message);
        }
        // Forward binary audio packet to viewers subscribed to this device
        wssViewer.clients.forEach((viewer) => {
          if (viewer.readyState === WebSocket.OPEN) {
            const targetDevId = getViewerTargetDeviceId(viewer);
            if (targetDevId === deviceId) {
              // Audio packet is tiny (~100-200B), avoid dropping unless congested > 512KB
              if (viewer.bufferedAmount < 512 * 1024) {
                viewer.send(message, { binary: true });
              }
            }
          }
        });
        return;
      }

      // ---- AI Agent: buffer raw PCM voice chunks from device ----
      if (deviceRecord.isCollectingVoice) {
        deviceRecord.audioChunks.push(Buffer.from(message));
        return; // don't treat voice PCM as a video frame
      }

      // ---- AI Agent: resolve pending screenshot promise ----
      if (deviceRecord.isPendingScreenshot && deviceRecord.screenshotResolve) {
        const jpegBase64 = Buffer.from(message).toString('base64');
        deviceRecord.isPendingScreenshot = false;
        const resolve = deviceRecord.screenshotResolve;
        deviceRecord.screenshotResolve = null;
        resolve(jpegBase64);
        return;
      }

      deviceRecord.stats.framesReceived++;
      deviceRecord.stats.bytesReceived += message.length;
      deviceRecord._secondFrames++;
      deviceRecord._secondBytes += message.length;

      // Cache SPS/PPS if present in this chunk
      if (extractSpsPps(message)) {
        deviceRecord.cachedConfig = Buffer.from(message);
      }

      // Forward binary H.264 frame to viewers subscribed to this device
      const isKeyOrConfig = isKeyFrameOrConfig(message);
      wssViewer.clients.forEach((viewer) => {
        if (viewer.readyState === WebSocket.OPEN) {
          const targetDevId = getViewerTargetDeviceId(viewer);
          if (targetDevId === deviceId) {
            // Drop P-frames if viewer socket buffer is congested (> 128 KB)
            if (!isKeyOrConfig && viewer.bufferedAmount > 128 * 1024) {
              return;
            }
            viewer.send(message, { binary: true });
          }
        }
      });
    } else {
      // JSON message from device
      try {
        const text = message.toString();
        const data = JSON.parse(text);

        if (data.type === 'PING') {
          ws.send(JSON.stringify({
            type: 'PONG',
            time: data.time || data.t,
            serverTime: Date.now()
          }));
        } else if (data.type === 'INIT') {
          if (data.deviceId) {
            activeDevices.delete(deviceId);
            deviceId = data.deviceId;
            deviceRecord.id = deviceId;
            activeDevices.set(deviceId, deviceRecord);
          }
          if (data.deviceName) deviceRecord.name = data.deviceName;
          if (data.username)   deviceRecord.username = data.username; // ← AI Agent
          if (data.width)  deviceRecord.width = data.width;
          if (data.height) deviceRecord.height = data.height;
          if (data.fps)    deviceRecord.fps = data.fps;
          if (data.bitrate) deviceRecord.bitrate = data.bitrate;
          if (data.cameraAllowed !== undefined) deviceRecord.cameraAllowed = Boolean(data.cameraAllowed);
          if (data.cameraFacing  !== undefined) deviceRecord.cameraFacing  = data.cameraFacing;
          if (data.cameraActive  !== undefined) deviceRecord.cameraActive  = Boolean(data.cameraActive);

          console.log(`[Device] Registered ${deviceRecord.name} [${deviceId}] user="${deviceRecord.username}" (${deviceRecord.width}x${deviceRecord.height} @ ${deviceRecord.fps}fps)`);
          broadcastDeviceListToViewers();

        } else if (data.type === 'CAMERA_STATUS') {
          if (data.cameraAllowed !== undefined) deviceRecord.cameraAllowed = Boolean(data.cameraAllowed);
          if (data.cameraFacing  !== undefined) deviceRecord.cameraFacing  = data.cameraFacing;
          if (data.cameraActive  !== undefined) deviceRecord.cameraActive  = Boolean(data.cameraActive);

          console.log(`[Device] Camera status for ${deviceId}: allowed=${deviceRecord.cameraAllowed}, facing=${deviceRecord.cameraFacing}, active=${deviceRecord.cameraActive}`);

          const camPayload = JSON.stringify({
            type: 'CAMERA_STATUS',
            deviceId: deviceId,
            cameraAllowed: deviceRecord.cameraAllowed,
            cameraFacing: deviceRecord.cameraFacing,
            cameraActive: deviceRecord.cameraActive
          });

          wssViewer.clients.forEach((viewer) => {
            if (viewer.readyState === WebSocket.OPEN) {
              const targetDevId = getViewerTargetDeviceId(viewer);
              if (targetDevId === deviceId) {
                viewer.send(camPayload);
              }
            }
          });

          broadcastDeviceListToViewers();

        // ---- AI Agent: voice command message handlers ----

        } else if (data.type === 'VOICE_START') {
          deviceRecord.audioChunks = [];
          deviceRecord.isCollectingVoice = true;
          if (data.username) deviceRecord.username = data.username;
          console.log(`[Agent][${deviceId}] Voice capture started (user: ${deviceRecord.username})`);
          sendToDevice(deviceRecord, { type: 'AGENT_STATUS', status: 'LISTENING', message: 'Listening...' });

        } else if (data.type === 'VOICE_END') {
          console.log(`[Agent][${deviceId}] Voice capture ended — ${deviceRecord.audioChunks.length} chunks buffered`);
          // Run pipeline asynchronously so this message handler returns immediately
          runAgentPipeline(deviceRecord).catch(err =>
            console.error(`[Agent][${deviceId}] Unhandled pipeline error:`, err.message)
          );

        } else if (data.type === 'ACTION_RESULT') {
          const success = data.success;
          const resultMsg = data.message || '';
          console.log(`[Agent][${deviceId}] ACTION_RESULT: success=${success} — ${resultMsg}`);
          sendToDevice(deviceRecord, { type: 'AGENT_STATUS', status: 'IDLE', message: '' });
        }
      } catch (err) {
        console.error('[Device] Error parsing message:', err.message);
      }
    }
  });

  ws.on('close', (code, reason) => {
    console.log(`[Device] Disconnected: ${deviceId} (${code} - ${reason})`);
    clearInterval(statsInterval);
    activeDevices.delete(deviceId);
    broadcastDeviceListToViewers();
  });

  ws.on('error', (err) => {
    console.error(`[Device Error ${deviceId}]`, err.message);
  });
});

// --------------------------------------------------------------------------
// Viewer WebSocket Handler (Used by HoneyMo Preview app & Web Dashboard)
// --------------------------------------------------------------------------
wssViewer.on('connection', (ws, req) => {
  setupHeartbeat(ws);

  const parsedUrl = url.parse(req.url, true);
  const targetDeviceId = parsedUrl.query.deviceId || null;
  ws.subscribedDeviceId = targetDeviceId;

  const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
  console.log(`[Viewer] Connected from ${clientIp}. Subscribed to: ${targetDeviceId || 'default (first device)'}`);

  // Send initial device list
  ws.send(JSON.stringify({
    type: 'DEVICE_LIST',
    devices: getDeviceList()
  }));

  // If already subscribed to a device, or default to the first active device, send cached config and request keyframe
  const effectiveDevId = getViewerTargetDeviceId(ws);
  if (effectiveDevId && activeDevices.has(effectiveDevId)) {
    const dev = activeDevices.get(effectiveDevId);
    sendCachedConfigAndKeyframe(ws, dev);
  }

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message.toString());

      if (data.type === 'PING') {
        ws.send(JSON.stringify({
          type: 'PONG',
          time: data.time || data.t,
          serverTime: Date.now()
        }));
      } else if (data.type === 'GET_DEVICES') {
        ws.send(JSON.stringify({
          type: 'DEVICE_LIST',
          devices: getDeviceList()
        }));
      } else if (data.type === 'SUBSCRIBE') {
        ws.subscribedDeviceId = data.deviceId;
        console.log(`[Viewer] Switched subscription to device: ${data.deviceId}`);
        if (activeDevices.has(data.deviceId)) {
          const dev = activeDevices.get(data.deviceId);
          sendCachedConfigAndKeyframe(ws, dev);
        }
      } else if (data.type === 'REQUEST_KEYFRAME') {
        const devId = data.deviceId || getViewerTargetDeviceId(ws);
        if (devId && activeDevices.has(devId)) {
          const dev = activeDevices.get(devId);
          if (dev.cachedConfig && ws.readyState === WebSocket.OPEN) {
            ws.send(dev.cachedConfig, { binary: true });
          }
          if (dev.cachedAudioConfig && ws.readyState === WebSocket.OPEN) {
            ws.send(dev.cachedAudioConfig, { binary: true });
          }
          if (dev.ws && dev.ws.readyState === WebSocket.OPEN) {
            dev.ws.send(JSON.stringify({ type: 'REQUEST_KEYFRAME' }));
            console.log(`[Viewer] Requested keyframe from device ${devId}`);
          }
        }
      } else if (data.type === 'SWITCH_CAMERA') {
        const devId = data.deviceId || getViewerTargetDeviceId(ws);
        if (devId && activeDevices.has(devId)) {
          const dev = activeDevices.get(devId);
          if (dev.ws && dev.ws.readyState === WebSocket.OPEN) {
            dev.ws.send(JSON.stringify({
              type: 'SWITCH_CAMERA',
              targetFacing: data.targetFacing || null
            }));
            console.log(`[Viewer] Forwarded SWITCH_CAMERA to device ${devId} (targetFacing: ${data.targetFacing || 'toggle'})`);
          }
        }
      }
    } catch (e) {
      console.error('[Viewer Message Error]', e.message);
    }
  });

  ws.on('close', () => {
    console.log(`[Viewer] Disconnected`);
  });
});

function sendCachedConfigAndKeyframe(viewerWs, dev) {
  if (!dev) return;
  if (viewerWs.readyState === WebSocket.OPEN) {
    try {
      viewerWs.send(JSON.stringify({
        type: 'CAMERA_STATUS',
        deviceId: dev.id,
        cameraAllowed: dev.cameraAllowed || false,
        cameraFacing: dev.cameraFacing || 'front',
        cameraActive: dev.cameraActive || false
      }));
    } catch (e) {}
  }
  if (dev.cachedConfig && viewerWs.readyState === WebSocket.OPEN) {
    viewerWs.send(dev.cachedConfig, { binary: true });
  }
  if (dev.cachedAudioConfig && viewerWs.readyState === WebSocket.OPEN) {
    viewerWs.send(dev.cachedAudioConfig, { binary: true });
  }
  if (dev.ws && dev.ws.readyState === WebSocket.OPEN) {
    try {
      dev.ws.send(JSON.stringify({ type: 'REQUEST_KEYFRAME' }));
    } catch (e) {}
  }
}

// Upgrade handling for WebSockets
server.on('upgrade', (request, socket, head) => {
  const pathname = url.parse(request.url).pathname;

  if (pathname === '/ws/device') {
    wssDevice.handleUpgrade(request, socket, head, (ws) => {
      wssDevice.emit('connection', ws, request);
    });
  } else if (pathname === '/ws/viewer' || pathname === '/ws') {
    wssViewer.handleUpgrade(request, socket, head, (ws) => {
      wssViewer.emit('connection', ws, request);
    });
  } else {
    socket.destroy();
  }
});

// --------------------------------------------------------------------------
// REST Endpoints & Render Healthcheck
// --------------------------------------------------------------------------
// Standard Render Health Check
app.get('/healthz', (req, res) => {
  res.status(200).json({
    status: 'healthy',
    uptime: process.uptime(),
    timestamp: new Date().toISOString()
  });
});

app.get('/api/devices', (req, res) => {
  res.json({
    status: 'ok',
    count: activeDevices.size,
    devices: getDeviceList()
  });
});

app.get('/api/devices/:id', (req, res) => {
  const dev = activeDevices.get(req.params.id);
  if (!dev) return res.status(404).json({ error: 'Device not found' });
  res.json({
    status: 'ok',
    device: {
      id: dev.id,
      name: dev.name,
      width: dev.width,
      height: dev.height,
      fps: dev.fps,
      bitrate: dev.bitrate,
      cameraAllowed: dev.cameraAllowed || false,
      cameraFacing: dev.cameraFacing || 'front',
      cameraActive: dev.cameraActive || false,
      connectedAt: dev.connectedAt,
      stats: dev.stats
    }
  });
});

app.get('/api/status', (req, res) => {
  res.json({
    status: 'ok',
    totalDevices: activeDevices.size,
    totalViewers: wssViewer.clients.size,
    uptime: process.uptime(),
    devices: getDeviceList()
  });
});

// ---- AI Agent: conversation history and session status per device ----
app.get('/api/agent/status/:deviceId', (req, res) => {
  const dev = activeDevices.get(req.params.deviceId);
  if (!dev) return res.status(404).json({ error: 'Device not found' });
  res.json({
    status: 'ok',
    deviceId: dev.id,
    username: dev.username || 'User',
    isCollectingVoice: dev.isCollectingVoice || false,
    isPendingScreenshot: dev.isPendingScreenshot || false,
    conversationTurns: Math.floor((dev.conversationHistory || []).length / 2),
    conversationHistory: (dev.conversationHistory || []).slice(-6), // last 3 exchanges
  });
});

function getLocalIps() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        addresses.push(net.address);
      }
    }
  }
  return addresses;
}

// Graceful shutdown handling for cloud hosts (Render/Heroku/Kubernetes)
process.on('SIGTERM', () => {
  console.log('[Server] SIGTERM received. Closing gracefully...');
  clearInterval(heartbeatInterval);
  server.close(() => {
    console.log('[Server] HTTP and WebSocket servers closed.');
    process.exit(0);
  });
});

process.on('SIGINT', () => {
  console.log('[Server] SIGINT received. Closing gracefully...');
  clearInterval(heartbeatInterval);
  server.close(() => {
    console.log('[Server] HTTP and WebSocket servers closed.');
    process.exit(0);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = getLocalIps();
  const renderHostname = process.env.RENDER_EXTERNAL_HOSTNAME || (process.env.RENDER ? 'honeymo-relay-server.onrender.com' : null);

  console.log('====================================================');
  console.log(` HoneyMo Server listening on port ${PORT}`);
  console.log(` Environment: ${process.env.NODE_ENV || 'development'}`);
  console.log(` Health check: http://0.0.0.0:${PORT}/healthz`);

  if (renderHostname) {
    console.log(` Public Render Cloud Endpoints:`);
    console.log(`   -> Web Dashboard:         https://${renderHostname}`);
    console.log(`   -> HoneyMo Streamer URL:  wss://${renderHostname}/ws/device`);
    console.log(`   -> HoneyMo Previewer URL: wss://${renderHostname}/ws/viewer`);
  } else {
    console.log(` Web Dashboard: http://localhost:${PORT}`);
    console.log(` Local IP Addresses for Android connection:`);
    ips.forEach(ip => {
      console.log(`   -> Device stream endpoint: ws://${ip}:${PORT}/ws/device`);
      console.log(`   -> Viewer endpoint:        ws://${ip}:${PORT}/ws/viewer`);
    });
  }
  console.log('====================================================');
});
// Lightweight health-check endpoint for keep-alive pings
app.get('/health', (req, res) => {
  res.status(200).send('OK');
});
