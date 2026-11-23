import { Layers3 } from 'lucide-react';

/** Exploded control-plane layers. Decorative geometry carries no health information. */
export function ForgeStack() {
  return (
    <div className="forge-stack" aria-hidden="true">
      <div className="forge-stack__plane forge-stack__base" />
      <div className="forge-stack__plane forge-stack__middle" />
      <div className="forge-stack__plane forge-stack__top">
        <Layers3 size={45} strokeWidth={1.4} />
      </div>
    </div>
  );
}
