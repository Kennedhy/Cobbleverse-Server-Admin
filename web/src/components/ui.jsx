import { Check, Info, LoaderCircle } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

export function Section({ title, description, children, className = '', action }) {
  return <section className={`section ${className}`}>
    <div className="section-head">
      <div><h2>{title}</h2>{description && <p>{description}</p>}</div>
      {action}
    </div>
    {children}
  </section>;
}

export function Field({ label, hint, children, className = '' }) {
  return <label className={`field ${className}`}>
    <span className="field-label">{label}</span>
    {children}
    {hint && <span className="field-hint">{hint}</span>}
  </label>;
}

export function Toggle({ label, description, checked, onChange, disabled = false }) {
  return <div className={`toggle-row ${disabled ? 'is-disabled' : ''}`}>
    <div className="toggle-copy"><strong>{label}</strong>{description && <span>{description}</span>}</div>
    <button className={`switch ${checked ? 'is-on' : ''}`} type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} onClick={() => onChange(!checked)}>
      <span />
    </button>
  </div>;
}

export function Notice({ children, tone = 'info' }) {
  return <div className={`notice notice-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
    <Info size={17} aria-hidden="true" />
    <span>{children}</span>
  </div>;
}

export function NumberField({ label, value, onChange, min = 0, max = 100, step = 1, suffix, hint, disabled = false }) {
  return <Field label={label} hint={hint}>
    <span className="number-wrap">
      <NumericInput value={value} min={min} max={max} step={step} disabled={disabled} onCommit={onChange} />
      {suffix && <span className="number-suffix">{suffix}</span>}
    </span>
  </Field>;
}

export function NumericInput({ value, min, max, step, onCommit, placeholder, disabled = false, ariaLabel }) {
  const [text, setText] = useState(value == null ? '' : String(value));
  const editing = useRef(false);
  useEffect(() => { if (!editing.current) setText(value == null ? '' : String(value)); }, [value]);
  const commit = () => {
    editing.current = false;
    const normalized = text.trim().replace(',', '.');
    if (!normalized) { onCommit(''); return; }
    const parsed = Number(normalized);
    if (!Number.isFinite(parsed)) { onCommit(''); return; }
    onCommit(parsed);
    setText(String(parsed));
  };
  return <input aria-label={ariaLabel} type="text" inputMode="decimal" value={text} placeholder={placeholder}
    min={min} max={max} step={step} disabled={disabled} onFocus={() => { editing.current = true; }}
    onChange={(event) => setText(event.target.value)} onBlur={commit} />;
}

export function CommitTextInput({ value, onCommit, ...props }) {
  const [text, setText] = useState(value ?? '');
  const editing = useRef(false);
  useEffect(() => { if (!editing.current) setText(value ?? ''); }, [value]);
  return <input {...props} type="text" value={text} onFocus={() => { editing.current = true; }}
    onChange={(event) => setText(event.target.value)} onBlur={() => {
      editing.current = false;
      if (onCommit(text) === false) setText(value ?? '');
    }} />;
}

export function SelectField({ label, value, options, onChange, hint, disabled = false }) {
  return <Field label={label} hint={hint}>
    <select value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
      {options.map((option) => typeof option === 'string'
        ? <option key={option} value={option}>{option}</option>
        : <option key={option.value} value={option.value}>{option.label}</option>)}
    </select>
  </Field>;
}

export function Choice({ selected, onClick, children, className = '', disabled = false }) {
  return <button type="button" className={`choice ${selected ? 'is-selected' : ''} ${className}`} aria-pressed={selected} disabled={disabled} onClick={onClick}>
    <span className="choice-mark">{selected ? <Check size={17} /> : null}</span>{children}
  </button>;
}

export function Loading({ children = 'Carregando…' }) {
  return <span className="loading"><LoaderCircle size={16} className="spin" />{children}</span>;
}
