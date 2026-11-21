import { extensionCall, onExtensionEvent } from './despia-extension.js';

const ROUTES = ['configure', 'set_token', 'logout', 'permissions', 'join', 'leave', 'set_muted', 'set_speaker', 'status'];
const EVENTS = {
  incoming: 'onStreamcallIncoming',
  connecting: 'onStreamcallConnecting',
  joined: 'onStreamcallJoined',
  ended: 'onStreamcallEnded',
  muted: 'onStreamcallMuted',
  audio_route: 'onStreamcallAudioRoute',
  error: 'onStreamcallError',
  token_needed: 'onStreamcallTokenNeeded'
};

function call(route, params = {}) {
  if (!ROUTES.includes(route)) return Promise.reject(new Error(`Unknown route ${route}`));
  const encoded = Object.fromEntries(Object.entries(params).map(([name, parameter]) =>
    [name, Array.isArray(parameter) ? parameter.join(',') : parameter]));
  return extensionCall('streamcall', route, encoded);
}

export function on(event, listener) {
  if (!EVENTS[event]) throw new Error(`Unknown event ${event}`);
  return onExtensionEvent(EVENTS[event], listener);
}

// Obtain app SDK configuration and user tokens from your own token server.
export const configure = ({ apiKey, userId, userName, token, tokenUrl, voipProvider, fcmProvider }) =>
  call('configure', { api_key: apiKey, user_id: userId, user_name: userName, token, token_url: tokenUrl,
    voip_provider: voipProvider, fcm_provider: fcmProvider });
export const setToken = (token, requestId) => call('set_token', { token, request_id: requestId });
export const logout = () => call('logout');
export const permissions = (request = false) => call('permissions', { request });
export const join = ({ callType = 'audio_call', callId, create = true, ring = false, members = [] }) =>
  call('join', { call_type: callType, call_id: callId, create, ring, members });
export const leave = () => call('leave');
export const setMuted = muted => call('set_muted', { muted });
export const setSpeaker = on => call('set_speaker', { on });
export const status = () => call('status');

// Without token_url, native code waits 10 seconds after token_needed before ending the call.
export function refreshTokensWith(fetchToken) {
  return on('token_needed', async ({ user_id, request_id }) => {
    try { await setToken(await fetchToken(user_id), request_id); }
    catch (error) { console.error('Stream token refresh failed', error.code || 'refresh_failed'); }
  });
}
