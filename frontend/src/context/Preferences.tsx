import { Toast } from '../components/Toast';
import {
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useState,
  type ReactNode,
} from 'react';

export const choices = {
  theme: ['system', 'light', 'dark'],
  accent: ['blue', 'violet', 'cyan', 'emerald', 'amber'],
  density: ['comfortable', 'compact'],
  motion: ['full', 'reduced'],
  sidebar: ['expanded', 'collapsed'],
} as const;

type Preferences = { [K in keyof typeof choices]: (typeof choices)[K][number] };
type Update = <K extends keyof Preferences>(key: K, value: Preferences[K]) => void;
const defaults: Preferences = {
  theme: 'system',
  accent: 'blue',
  density: 'comfortable',
  motion: 'full',
  sidebar: 'expanded',
};
const storageKey = 'cloudforge.appearance.v1';

function read(): Preferences {
  const result = { ...defaults };
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || '{}');
    function restore<K extends keyof Preferences>(key: K) {
      if ((choices[key] as readonly string[]).includes(saved?.[key])) result[key] = saved[key];
    }
    (Object.keys(choices) as (keyof Preferences)[]).forEach(restore);
  } catch {
    /* Storage may be denied or contain invalid JSON. */
  }
  return result;
}

const Context = createContext<{ prefs: Preferences; update: Update; reset: () => void } | null>(
  null,
);

export function PreferenceProvider({ children }: { children: ReactNode }) {
  const [prefs, setPrefs] = useState(read);
  const [storageError, setStorageError] = useState(false);
  useLayoutEffect(() => {
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    const apply = () =>
      Object.assign(document.documentElement.dataset, prefs, {
        theme: prefs.theme === 'system' ? (media?.matches ? 'dark' : 'light') : prefs.theme,
      });
    apply();
    media?.addEventListener('change', apply);
    return () => media?.removeEventListener('change', apply);
  }, [prefs]);
  useEffect(() => {
    try {
      localStorage.setItem(storageKey, JSON.stringify(prefs));
    } catch {
      setStorageError(true);
    }
  }, [prefs]);
  const update: Update = (key, value) => setPrefs((current) => ({ ...current, [key]: value }));
  return (
    <Context.Provider value={{ prefs, update, reset: () => setPrefs({ ...defaults }) }}>
      {children}
      {storageError && (
        <Toast
          tone="error"
          message="Browser storage is unavailable. Appearance changes apply for this visit."
          onDismiss={() => setStorageError(false)}
        />
      )}
    </Context.Provider>
  );
}

export function usePreferences() {
  const value = useContext(Context);
  if (!value) throw new Error('Appearance controls require PreferenceProvider');
  return value;
}
