/**
 * HoneyMo AI Orchestrator
 * Handles STT (Groq Whisper) → LLM reasoning (Groq LLaMA / Vision) → Structured tool call output
 *
 * All communication with Groq Cloud API.
 * Set GROQ_API_KEY environment variable before running.
 */

'use strict';

// node-fetch v3 dynamic import shim for CommonJS
const fetch = (...args) => import('node-fetch').then(({ default: f }) => f(...args));

const GROQ_API_BASE = 'https://api.groq.com/openai/v1';
const GROQ_API_KEY  = process.env.GROQ_API_KEY || '';

// ---- Common app name → Android package alias map ----
const PACKAGE_ALIAS_MAP = {
  whatsapp:    'com.whatsapp',
  telegram:    'org.telegram.messenger',
  instagram:   'com.instagram.android',
  youtube:     'com.google.android.youtube',
  maps:        'com.google.android.apps.maps',
  'google maps': 'com.google.android.apps.maps',
  settings:    'com.android.settings',
  chrome:      'com.android.chrome',
  camera:      'com.android.camera2',
  gallery:     'com.google.android.apps.photos',
  photos:      'com.google.android.apps.photos',
  gmail:       'com.google.android.gm',
  contacts:    'com.android.contacts',
  phone:       'com.android.dialer',
  dialer:      'com.android.dialer',
  messages:    'com.google.android.apps.messaging',
  sms:         'com.google.android.apps.messaging',
  calendar:    'com.google.android.calendar',
  clock:       'com.google.android.deskclock',
  calculator:  'com.google.android.calculator',
  play:        'com.android.vending',
  'play store':'com.android.vending',
  spotify:     'com.spotify.music',
  twitter:     'com.twitter.android',
  x:           'com.twitter.android',
  facebook:    'com.facebook.katana',
  tiktok:      'com.zhiliaoapp.musically',
  netflix:     'com.netflix.mediaclient',
  uber:        'com.ubercab',
  snapchat:    'com.snapchat.android',
  discord:     'com.discord',
  zoom:        'us.zoom.videomeetings',
  teams:       'com.microsoft.teams',
};

// ---- Tool schema definitions for the LLM ----
const TOOLS = [
  {
    type: 'function',
    function: {
      name: 'tap_node',
      description: 'Tap (click) on a UI element. Prefer textMatch or viewId when available.',
      parameters: {
        type: 'object',
        properties: {
          textMatch:  { type: 'string', description: 'Visible text of the element to tap' },
          viewId:     { type: 'string', description: 'Android resource view ID, e.g. com.whatsapp:id/send_btn' },
          fallbackX:  { type: 'number', description: 'Normalised X coordinate (0.0–1.0) as fallback' },
          fallbackY:  { type: 'number', description: 'Normalised Y coordinate (0.0–1.0) as fallback' },
        },
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'type_text',
      description: 'Type text into the currently focused or the first visible input field.',
      parameters: {
        type: 'object',
        required: ['text'],
        properties: {
          text: { type: 'string', description: 'The text to type' },
        },
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'launch_app',
      description: 'Open an application by its Android package name or common name.',
      parameters: {
        type: 'object',
        required: ['packageName'],
        properties: {
          packageName: { type: 'string', description: 'Package name or common name (e.g. "whatsapp" or "com.whatsapp")' },
        },
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'scroll',
      description: 'Scroll the screen in a direction.',
      parameters: {
        type: 'object',
        required: ['direction'],
        properties: {
          direction: { type: 'string', enum: ['up', 'down', 'left', 'right'] },
          amount:    { type: 'number', description: 'Proportion of screen to scroll (0.1–1.0), default 0.5' },
        },
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'press_back',
      description: 'Press the Android Back button.',
      parameters: { type: 'object', properties: {} },
    },
  },
  {
    type: 'function',
    function: {
      name: 'press_home',
      description: 'Press the Android Home button to go to the home screen.',
      parameters: { type: 'object', properties: {} },
    },
  },
  {
    type: 'function',
    function: {
      name: 'speak_response',
      description: 'Speak a reply to the user without taking any device action.',
      parameters: {
        type: 'object',
        required: ['text'],
        properties: {
          text: { type: 'string', description: 'What Jarvis should say to the user' },
        },
      },
    },
  },
];

// ---- Resolve package alias (e.g. "whatsapp" → "com.whatsapp") ----
function resolvePackageName(nameOrPackage) {
  if (!nameOrPackage) return nameOrPackage;
  const lower = nameOrPackage.trim().toLowerCase();
  return PACKAGE_ALIAS_MAP[lower] || nameOrPackage;
}

// ---- Main orchestrator function ----
/**
 * @param {object}   opts
 * @param {string}   opts.username            Display name of the user
 * @param {string}   opts.transcript          STT transcript of the voice command
 * @param {string|null} opts.screenshotBase64 Base64-encoded JPEG screenshot (or null)
 * @param {Array}    opts.conversationHistory  Array of { role, content } objects (last 10)
 * @returns {Promise<{ action: string, parameters: object, thought: string }>}
 */
async function processVoiceAndScreen({ username, transcript, screenshotBase64, conversationHistory }) {
  if (!GROQ_API_KEY) {
    console.warn('[Orchestrator] GROQ_API_KEY is not set — returning fallback response');
    return { action: 'speak_response', parameters: { text: 'My AI key is not configured yet. Please set the GROQ_API_KEY on the server.' }, thought: 'No API key' };
  }

  try {
    // Choose model — vision if we have a screenshot, fast text model otherwise
    const model = screenshotBase64
      ? 'llama-3.2-11b-vision-preview'
      : 'llama-3.3-70b-versatile';

    // Build system message
    const systemPrompt = `You are Jarvis, an intelligent AI assistant that controls an Android device for ${username}. 
You receive voice commands from the user and a screenshot of the current screen.
Your job is to figure out the best sequence of actions to fulfil the request and call the appropriate tool.
Always prefer using tap_node with textMatch over raw coordinates.
If the request is conversational (e.g. "what time is it?", "how are you?"), use speak_response.
Think step by step before deciding the action. Be concise.`;

    // Build user content — text + optional image
    const userContent = screenshotBase64
      ? [
          { type: 'text', text: `User said: "${transcript}"\n\nHere is the current screen:` },
          { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${screenshotBase64}` } },
        ]
      : `User said: "${transcript}"`;

    // Assemble messages: system + history (capped) + current user turn
    const cappedHistory = (conversationHistory || []).slice(-8); // keep last 8 for token budget
    const messages = [
      { role: 'system', content: systemPrompt },
      ...cappedHistory,
      { role: 'user', content: userContent },
    ];

    // Call Groq chat completions
    const response = await fetch(`${GROQ_API_BASE}/chat/completions`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${GROQ_API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model,
        messages,
        tools: TOOLS,
        tool_choice: 'auto',
        temperature: 0.2,
        max_tokens: 512,
      }),
    });

    if (!response.ok) {
      const errText = await response.text();
      console.error('[Orchestrator] Groq API error:', response.status, errText);
      throw new Error(`Groq API returned ${response.status}`);
    }

    const data = await response.json();
    const choice = data.choices?.[0];
    const message = choice?.message;

    if (!message) throw new Error('Empty response from Groq');

    const thought = message.content || '';

    // Check if the model made a tool call
    if (message.tool_calls && message.tool_calls.length > 0) {
      const toolCall = message.tool_calls[0]; // execute first tool call
      const actionName = toolCall.function.name;
      let parameters = {};

      try {
        parameters = JSON.parse(toolCall.function.arguments || '{}');
      } catch (e) {
        console.warn('[Orchestrator] Failed to parse tool arguments:', toolCall.function.arguments);
      }

      // Resolve package alias for launch_app
      if (actionName === 'launch_app' && parameters.packageName) {
        parameters.packageName = resolvePackageName(parameters.packageName);
      }

      console.log(`[Orchestrator] Tool call: ${actionName}`, parameters);
      return { action: actionName, parameters, thought };
    }

    // No tool call — treat as a plain spoken response
    const spokenText = thought || 'Done.';
    console.log('[Orchestrator] Plain response:', spokenText.substring(0, 80));
    return { action: 'speak_response', parameters: { text: spokenText }, thought };

  } catch (err) {
    console.error('[Orchestrator] processVoiceAndScreen error:', err.message);
    return {
      action: 'speak_response',
      parameters: { text: 'Sorry, I had trouble processing that. Please try again.' },
      thought: `Error: ${err.message}`,
    };
  }
}

// ---- Groq Whisper STT ----
/**
 * Transcribe a WAV buffer using Groq Whisper.
 * @param {Buffer} wavBuffer  A complete WAV file buffer (with 44-byte RIFF header)
 * @returns {Promise<string>} Transcript text, or empty string on failure
 */
async function transcribeAudio(wavBuffer) {
  if (!GROQ_API_KEY) {
    console.warn('[Orchestrator] GROQ_API_KEY not set — skipping STT');
    return '';
  }

  try {
    // Dynamic import FormData from the form-data package
    const FormData = (await import('form-data')).default;

    const form = new FormData();
    form.append('file', wavBuffer, { filename: 'audio.wav', contentType: 'audio/wav' });
    form.append('model', 'whisper-large-v3-turbo');
    form.append('response_format', 'json');
    form.append('language', 'en');

    const response = await fetch(`${GROQ_API_BASE}/audio/transcriptions`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${GROQ_API_KEY}`,
        ...form.getHeaders(),
      },
      body: form,
    });

    if (!response.ok) {
      const errText = await response.text();
      console.error('[Orchestrator] Whisper STT error:', response.status, errText);
      return '';
    }

    const data = await response.json();
    const transcript = (data.text || '').trim();
    console.log(`[Orchestrator] STT transcript: "${transcript}"`);
    return transcript;

  } catch (err) {
    console.error('[Orchestrator] transcribeAudio error:', err.message);
    return '';
  }
}

module.exports = { processVoiceAndScreen, transcribeAudio, resolvePackageName };
