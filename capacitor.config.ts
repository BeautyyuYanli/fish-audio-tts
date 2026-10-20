import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.fishaudio.tts',
  appName: 'Fish Audio TTS',
  webDir: 'dist',
  // Bridge debug logs include method arguments, which may contain an API key while saving.
  loggingBehavior: 'none',
  android: { webContentsDebuggingEnabled: false },
  plugins: { SystemBars: { insetsHandling: 'native' } },
};

export default config;
