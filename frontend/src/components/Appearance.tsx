import { useState } from 'react';
import { Toast } from './Toast';
import { Palette } from 'lucide-react';
import { choices, usePreferences } from '../context/Preferences';
import { DashboardCard } from './DashboardCard';

const fields = {
  theme: ['Theme', 'System follows the appearance of your device.'],
  accent: ['Accent color', 'Choose the color of links, primary actions and selected items.'],
  density: ['Interface density', 'Adjust spacing in navigation, panels and table rows.'],
  motion: [
    'Motion',
    'Reduce interface animation. Your device’s reduced-motion setting takes priority.',
  ],
  sidebar: ['Desktop sidebar', 'Show full navigation labels or an icon rail on large screens.'],
} as const;
const accentNames = {
  blue: 'Electric blue',
  violet: 'Royal violet',
  cyan: 'Cyan',
  emerald: 'Emerald',
  amber: 'Amber',
};

export function Appearance() {
  const { prefs, update, reset } = usePreferences();
  const [resetNotice, setResetNotice] = useState(false);
  return (
    <DashboardCard
      title="Appearance & Experience"
      subtitle="Preferences apply immediately in this browser."
      icon={<Palette size={18} />}
      headerAction={
        <button
          className="btn btn-sm"
          onClick={() => {
            reset();
            setResetNotice(true);
          }}
        >
          Reset defaults
        </button>
      }
    >
      {resetNotice && (
        <Toast
          tone="info"
          message="Default appearance restored."
          onDismiss={() => setResetNotice(false)}
        />
      )}
      {(Object.keys(fields) as (keyof typeof fields)[]).map((key) => (
        <div className="cf-preference" key={key}>
          <div>
            <label htmlFor={'pref-' + key}>{fields[key][0]}</label>
            <p id={'hint-' + key}>{fields[key][1]}</p>
          </div>
          <select
            className="cf-select"
            id={'pref-' + key}
            aria-describedby={'hint-' + key}
            value={prefs[key]}
            onChange={(event) => {
              const value = choices[key].find((choice) => choice === event.target.value);
              if (value) {
                setResetNotice(false);
                update(key, value);
              }
            }}
          >
            {choices[key].map((value) => (
              <option key={value} value={value}>
                {key === 'accent'
                  ? accentNames[value as keyof typeof accentNames]
                  : value[0].toUpperCase() + value.slice(1)}
              </option>
            ))}
          </select>
        </div>
      ))}
    </DashboardCard>
  );
}
