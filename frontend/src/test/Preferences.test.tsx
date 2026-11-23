import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it } from 'vitest';
import { Appearance } from '../components/Appearance';
import { PreferenceProvider } from '../context/Preferences';

const mount = () =>
  render(
    <PreferenceProvider>
      <Appearance />
    </PreferenceProvider>,
  );
const storageKey = 'cloudforge.appearance.v1';

afterEach(() => {
  cleanup();
  localStorage.removeItem(storageKey);
});

it('recovers from malformed and partially invalid saved preferences', () => {
  localStorage.setItem(storageKey, '{');
  const first = mount();
  expect(screen.getByLabelText('Theme')).toHaveValue('system');
  first.unmount();
  localStorage.setItem(
    storageKey,
    JSON.stringify({ theme: 'dark', accent: 'invalid', sidebar: null }),
  );
  mount();
  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(screen.getByLabelText('Accent color')).toHaveValue('blue');
  expect(screen.getByLabelText('Desktop sidebar')).toHaveValue('expanded');
});

it('applies preferences immediately and restores the defaults after a remount', async () => {
  const user = userEvent.setup();
  const first = mount();
  await user.selectOptions(screen.getByLabelText('Theme'), 'dark');
  await user.selectOptions(screen.getByLabelText('Accent color'), 'emerald');
  expect(document.documentElement.dataset.accent).toBe('emerald');
  first.unmount();
  mount();
  expect(screen.getByLabelText('Theme')).toHaveValue('dark');
  expect(screen.getByLabelText('Accent color')).toHaveValue('emerald');
  await user.click(screen.getByRole('button', { name: 'Reset defaults' }));
  expect(JSON.parse(localStorage.getItem(storageKey)!)).toMatchObject({
    theme: 'system',
    accent: 'blue',
  });
});
