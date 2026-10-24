// Small SVG chart kit for the Reports page: bars (grouped or stacked), lines, horizontal bars, a Sankey.
// Marks follow one spec: 4px rounded data ends on the baseline, 2px surface gaps between stacked
// segments, 2px lines, recessive grid, and a per-band (bars) or crosshair (lines) tooltip.
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { formatMoney } from '../../lib/money';

/** Fixed categorical order; series keep their slot when others are filtered away. */
export const SERIES = ['var(--series-1)', 'var(--series-2)', 'var(--series-3)', 'var(--series-4)', 'var(--series-5)', 'var(--series-6)'];
export const OTHER = 'var(--series-other)';

export function useWidth<T extends HTMLElement>(fallback = 640) {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(fallback);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    setWidth(el.clientWidth || fallback);
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(([e]) => e && setWidth(Math.max(240, e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, [fallback]);
  return [ref, width] as const;
}

/** "Nice" axis ticks covering min..max. */
export function ticks(min: number, max: number, count = 4): number[] {
  const span = max - min || 1;
  const raw = span / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? raw;
  const out: number[] = [];
  for (let v = Math.floor(min / step) * step; v <= max + step * 0.001; v += step) out.push(v);
  if (out[out.length - 1]! < max) out.push(out[out.length - 1]! + step);
  return out;
}

/** Compact currency for axis labels ("€1.2K"). */
export function compact(milliunits: number, currency: string) {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 }).format(milliunits / 1000);
  } catch {
    return String(Math.round(milliunits / 1000));
  }
}

/** Whole currency units, for summaries ("€1,234"). */
export function money0(milliunits: number, currency: string) {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency, maximumFractionDigits: 0, minimumFractionDigits: 0 }).format(milliunits / 1000);
  } catch {
    return formatMoney(milliunits, currency);
  }
}

export type TipData = { title: string; rows: [string, string][]; note?: string };
type Tip = (TipData & { x: number; y: number }) | null;

export function Tooltip({ tip, width }: { tip: Tip; width: number }) {
  if (!tip) return null;
  const x = Math.min(Math.max(tip.x, 90), width - 90);
  return (
    <div className="tooltip" style={{ left: x, top: tip.y }}>
      <div className="tt-title">{tip.title}</div>
      {tip.rows.map(([k, v], i) => (
        <div className="tt-row" key={k + i}>
          <span>{k}</span>
          <b>{v}</b>
        </div>
      ))}
      {tip.note && <div className="tt-note">{tip.note}</div>}
    </div>
  );
}

export function Legend({ items }: { items: { label: string; color: string; dashed?: boolean }[] }) {
  return (
    <div className="legend legend-wrap">
      {items.map((s) => (
        <span className="key" key={s.label}>
          <span className={`swatch${s.dashed ? ' dashed' : ''}`} style={{ background: s.dashed ? undefined : s.color, borderColor: s.color }} /> {s.label}
        </span>
      ))}
    </div>
  );
}

/** A bar with a rounded data end and a square baseline; y1 < y0. */
function barPath(x: number, w: number, y0: number, y1: number, r = 4) {
  const h = y0 - y1;
  const rr = Math.min(r, h, w / 2);
  return `M${x},${y0} V${y1 + rr} Q${x},${y1} ${x + rr},${y1} H${x + w - rr} Q${x + w},${y1} ${x + w},${y1 + rr} V${y0} Z`;
}

export type BarSeries = { key: string; label: string; color: string; values: number[] };

const PAD = { top: 14, right: 12, bottom: 26, left: 56 };

export function BarChart({
  labels,
  series,
  stacked = false,
  height = 240,
  yFormat,
  xLabel,
  faded,
  refLines = [],
  band,
  line,
  tip,
  ariaLabel,
  maxBar = 26,
}: {
  labels: string[];
  series: BarSeries[];
  stacked?: boolean;
  height?: number;
  yFormat: (v: number) => string;
  /** Axis label for band i, or null to skip it (default: thinned labels). */
  xLabel?: (i: number) => string | null;
  /** Draw band i lighter (e.g. the running month). */
  faded?: (i: number) => boolean;
  refLines?: { value: number; color: string; label: string }[];
  band?: { from: number; to: number; color: string };
  line?: { values: (number | null)[]; color: string };
  tip: (i: number) => TipData;
  ariaLabel: string;
  maxBar?: number;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const n = labels.length;
  const totals = labels.map((_, i) =>
    stacked ? series.reduce((a, s) => a + Math.max(0, s.values[i] ?? 0), 0) : Math.max(0, ...series.map((s) => s.values[i] ?? 0)),
  );
  const max = Math.max(1, ...totals, ...refLines.map((r) => r.value), ...(line?.values.map((v) => v ?? 0) ?? []), band?.to ?? 0);
  const yt = ticks(0, max);
  const top = yt[yt.length - 1]!;
  const innerW = Math.max(10, width - PAD.left - PAD.right);
  const innerH = height - PAD.top - PAD.bottom;
  const bandW = innerW / Math.max(n, 1);
  const y = (v: number) => PAD.top + innerH - (v / top) * innerH;
  const every = Math.max(1, Math.ceil(n / Math.max(1, Math.floor(innerW / 56))));
  const label = xLabel ?? ((i: number) => (i % every === 0 ? labels[i]! : null));
  const k = stacked ? 1 : Math.max(1, series.length);
  const bw = Math.max(2, Math.min(maxBar, (bandW - (n > 40 ? 2 : 8)) / k - (k > 1 ? 2 : 0)));
  const cx = (i: number) => PAD.left + bandW * i + bandW / 2;
  const h = hover === null ? null : tip(hover);

  return (
    <div className="chart-wrap" ref={ref} onMouseLeave={() => setHover(null)}>
      <svg className="chart" width={width} height={height} role="img" aria-label={ariaLabel}>
        {band && <rect x={PAD.left} width={innerW} y={y(band.to)} height={y(band.from) - y(band.to)} fill={band.color} opacity={0.12} />}
        {yt.map((v) => (
          <g key={v}>
            <line className="gridline" x1={PAD.left} x2={width - PAD.right} y1={y(v)} y2={y(v)} />
            <text x={PAD.left - 8} y={y(v) + 4} textAnchor="end">
              {yFormat(v)}
            </text>
          </g>
        ))}
        {labels.map((_, i) => {
          const op = faded?.(i) ? 0.45 : 1;
          const marks: ReactNode[] = [];
          if (stacked) {
            let acc = 0;
            const visible = series.filter((s) => (s.values[i] ?? 0) > 0);
            visible.forEach((s, j) => {
              const v = s.values[i]!;
              const y0 = y(acc) - (acc > 0 ? 2 : 0);
              const y1 = y(acc + v);
              acc += v;
              if (y0 - y1 <= 0.5) return;
              const x = cx(i) - bw / 2;
              marks.push(
                j === visible.length - 1 ? (
                  <path key={s.key} d={barPath(x, bw, y0, y1)} fill={s.color} opacity={op} />
                ) : (
                  <rect key={s.key} x={x} width={bw} y={y1} height={y0 - y1} fill={s.color} opacity={op} />
                ),
              );
            });
          } else {
            series.forEach((s, j) => {
              const v = s.values[i] ?? 0;
              if (v <= 0) return;
              const x = cx(i) - (k * bw + (k - 1) * 2) / 2 + j * (bw + 2);
              marks.push(<path key={s.key} d={barPath(x, bw, y(0), y(v), n > 60 ? 2 : 4)} fill={s.color} opacity={op} />);
            });
          }
          const l = label(i);
          return (
            <g key={i}>
              {hover === i && <rect x={PAD.left + bandW * i} y={PAD.top} width={bandW} height={innerH} fill="var(--surface-hover)" />}
              {marks}
              {l && (
                <text x={cx(i)} y={height - 8} textAnchor="middle">
                  {l}
                </text>
              )}
            </g>
          );
        })}
        {line && (
          <>
            <polyline points={linePoints(line.values, cx, y)} fill="none" stroke="var(--surface)" strokeWidth={5} strokeLinejoin="round" />
            <polyline points={linePoints(line.values, cx, y)} fill="none" stroke={line.color} strokeWidth={2} strokeLinejoin="round" />
          </>
        )}
        {refLines.map((r) => (
          <g key={r.label}>
            <line x1={PAD.left} x2={width - PAD.right} y1={y(r.value)} y2={y(r.value)} stroke={r.color} strokeWidth={2} strokeDasharray="6 4" />
            <text x={PAD.left + 6} y={y(r.value) - 5} style={{ fill: 'var(--text-2)' }}>
              {r.label}
            </text>
          </g>
        ))}
        <line className="axis" x1={PAD.left} x2={width - PAD.right} y1={y(0)} y2={y(0)} />
        {labels.map((_, i) => (
          <rect
            key={i}
            x={PAD.left + bandW * i}
            y={PAD.top}
            width={bandW}
            height={innerH}
            fill="transparent"
            onMouseEnter={() => setHover(i)}
            onClick={() => setHover(i)}
          />
        ))}
      </svg>
      <Tooltip tip={h && hover !== null ? { ...h, x: cx(hover), y: y(totals[hover] ?? 0) } : null} width={width} />
    </div>
  );
}

function linePoints(values: (number | null)[], x: (i: number) => number, y: (v: number) => number) {
  return values
    .map((v, i) => (v === null ? null : `${x(i)},${y(v)}`))
    .filter(Boolean)
    .join(' ');
}

export type LineSeries = { key: string; label: string; color: string; values: number[]; dashed?: boolean; area?: boolean };

/** Lines over a shared x axis (index positions), with a crosshair tooltip. */
export function LineChart({
  count,
  series,
  height = 260,
  yFormat,
  xLabel,
  markers = [],
  tip,
  ariaLabel,
}: {
  count: number;
  series: LineSeries[];
  height?: number;
  yFormat: (v: number) => string;
  xLabel: (i: number) => string | null;
  markers?: { at: number; label: string }[];
  tip: (i: number) => TipData;
  ariaLabel: string;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const all = series.flatMap((s) => s.values);
  const yt = ticks(Math.min(0, ...all), Math.max(1, ...all));
  const lo = yt[0]!;
  const hi = yt[yt.length - 1]!;
  const innerW = Math.max(10, width - PAD.left - PAD.right);
  const innerH = height - PAD.top - PAD.bottom;
  const x = (i: number) => PAD.left + (count <= 1 ? innerW / 2 : (innerW * i) / (count - 1));
  const y = (v: number) => PAD.top + innerH - ((v - lo) / (hi - lo || 1)) * innerH;
  const pts = (vals: number[]) => vals.map((v, i) => `${x(i)},${y(v)}`).join(' ');
  const h = hover === null ? null : tip(hover);
  const focus = series.find((s) => !s.dashed) ?? series[0];

  return (
    <div
      className="chart-wrap"
      ref={ref}
      onMouseLeave={() => setHover(null)}
      onMouseMove={(e) => {
        const rect = e.currentTarget.getBoundingClientRect();
        const i = count <= 1 ? 0 : Math.round(((e.clientX - rect.left - PAD.left) / innerW) * (count - 1));
        setHover(Math.max(0, Math.min(count - 1, i)));
      }}
    >
      <svg className="chart" width={width} height={height} role="img" aria-label={ariaLabel}>
        {yt.map((v) => (
          <g key={v}>
            <line className="gridline" x1={PAD.left} x2={width - PAD.right} y1={y(v)} y2={y(v)} />
            <text x={PAD.left - 8} y={y(v) + 4} textAnchor="end">
              {yFormat(v)}
            </text>
          </g>
        ))}
        {Array.from({ length: count }, (_, i) => {
          const l = xLabel(i);
          return l ? (
            <text key={i} x={x(i)} y={height - 8} textAnchor="middle">
              {l}
            </text>
          ) : null;
        })}
        {markers.map((m, i) => (
          <g key={m.label}>
            <line x1={x(m.at)} x2={x(m.at)} y1={PAD.top} y2={PAD.top + innerH} stroke="var(--text-3)" strokeDasharray="3 4" />
            <text x={x(m.at) + 5} y={PAD.top + 10 + i * 14} style={{ fill: 'var(--text-2)' }}>
              {m.label}
            </text>
          </g>
        ))}
        {series
          .filter((s) => s.area)
          .map((s) => (
            <path key={s.key + 'a'} d={`M${x(0)},${y(Math.max(lo, 0))} L${pts(s.values).replaceAll(' ', ' L')} L${x(s.values.length - 1)},${y(Math.max(lo, 0))} Z`} fill={s.color} opacity={0.12} />
          ))}
        {series.map((s) => (
          <polyline
            key={s.key}
            points={pts(s.values)}
            fill="none"
            stroke={s.color}
            strokeWidth={2}
            strokeDasharray={s.dashed ? '5 4' : undefined}
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ))}
        {hover !== null && <line className="axis" x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + innerH} />}
        {hover !== null &&
          series.map((s) => <circle key={s.key} cx={x(hover)} cy={y(s.values[hover] ?? 0)} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />)}
        <line className="axis" x1={PAD.left} x2={width - PAD.right} y1={y(Math.max(lo, 0))} y2={y(Math.max(lo, 0))} />
      </svg>
      <Tooltip tip={h && hover !== null && focus ? { ...h, x: x(hover), y: y(focus.values[hover] ?? 0) } : null} width={width} />
    </div>
  );
}

/** Horizontal bars with an optional highlighted part (e.g. the subscription share) and expandable details. */
export function HBars({
  rows,
  format,
  partLabel,
}: {
  rows: { key: string; label: string; sub?: string; value: number; part?: number; share?: string; details?: ReactNode }[];
  format: (v: number) => string;
  partLabel?: string;
}) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <div className="hbar-list" role="list">
      {rows.map((r) => {
        const part = Math.max(0, Math.min(r.part ?? 0, r.value));
        const body = (
          <>
            <span className="label">
              {r.label}
              {r.sub && <small>{r.sub}</small>}
            </span>
            <span className="track" title={`${r.label}: ${format(r.value)}${part ? ` · ${partLabel} ${format(part)}` : ''}`}>
              {part > 0 && <span className="fill part" style={{ width: `${(part / max) * 100}%` }} />}
              <span className="fill" style={{ left: `calc(${(part / max) * 100}% + ${part > 0 ? 2 : 0}px)`, width: `max(2px, calc(${((r.value - part) / max) * 100}% - ${part > 0 ? 2 : 0}px))` }} />
            </span>
            <span className="value">
              {format(r.value)}
              {r.share && <small> {r.share}</small>}
            </span>
          </>
        );
        return r.details ? (
          <details className="hbar-item" key={r.key} role="listitem">
            <summary className="hbar">{body}</summary>
            <div className="hbar-details">{r.details}</div>
          </details>
        ) : (
          <div className="hbar" key={r.key} role="listitem">
            {body}
          </div>
        );
      })}
    </div>
  );
}

export type SankeyNode = { id: string; label: string; value: number; color: string; column: number; note?: string };
export type SankeyLink = { from: string; to: string; value: number; color: string };

/** A left-to-right flow diagram; nodes are stacked per column in the given order. */
export function Sankey({ nodes, links, format, ariaLabel }: { nodes: SankeyNode[]; links: SankeyLink[]; format: (n: SankeyNode) => string; ariaLabel: string }) {
  const [ref, width] = useWidth<HTMLDivElement>(900);
  const cols = Math.max(...nodes.map((n) => n.column)) + 1;
  const W = Math.max(width, 640);
  const labelW = 190;
  const NW = 12;
  const gap = 10;
  const colX = (c: number) => 8 + ((W - labelW - NW - 16) * c) / Math.max(1, cols - 1);
  const colTotal = (c: number) => nodes.filter((n) => n.column === c).reduce((a, n) => a + Math.max(0, n.value), 0);
  const maxTotal = Math.max(1, ...Array.from({ length: cols }, (_, c) => colTotal(c)));
  const lastCount = nodes.filter((n) => n.column === cols - 1).length;
  const H = Math.max(320, lastCount * 40 + 20);
  const maxCount = Math.max(...Array.from({ length: cols }, (_, c) => nodes.filter((n) => n.column === c).length));
  const scale = (H - 16 - gap * (maxCount - 1)) / maxTotal;
  const pos = new Map<string, { x: number; y: number; h: number; out: number; in: number }>();
  for (let c = 0; c < cols; c++) {
    const col = nodes.filter((n) => n.column === c);
    const slot = c === cols - 1 ? 34 : 0;
    const heights = col.map((n) => Math.max(2, n.value * scale));
    const slots = heights.map((h) => Math.max(h, slot));
    const g = c === cols - 1 ? 6 : gap;
    const total = slots.reduce((a, b) => a + b, 0) + g * (col.length - 1);
    let yy = Math.max(8, (H - total) / 2);
    col.forEach((n, i) => {
      const top = yy + (slots[i]! - heights[i]!) / 2;
      pos.set(n.id, { x: colX(c), y: top, h: heights[i]!, out: top, in: top });
      yy += slots[i]! + g;
    });
  }
  const paths = links.map((l, i) => {
    const s = pos.get(l.from);
    const t = pos.get(l.to);
    if (!s || !t || l.value <= 0) return null;
    const h = l.value * scale;
    const x0 = s.x + NW;
    const x1 = t.x;
    const m = (x0 + x1) / 2;
    const y0 = s.out;
    const y1 = t.in;
    s.out += h;
    t.in += h;
    return <path key={i} d={`M${x0},${y0} C${m},${y0} ${m},${y1} ${x1},${y1} L${x1},${y1 + h} C${m},${y1 + h} ${m},${y0 + h} ${x0},${y0 + h} Z`} fill={l.color} opacity={0.25} />;
  });
  return (
    <div className="chart-wrap sankey-wrap" ref={ref}>
      <svg className="chart" width={W} height={H} role="img" aria-label={ariaLabel}>
        {paths}
        {nodes.map((n) => {
          const p = pos.get(n.id)!;
          if (n.value <= 0) return null;
          const tx = p.x + NW + 8;
          const cy = p.y + p.h / 2;
          return (
            <g key={n.id}>
              <rect x={p.x} y={p.y} width={NW} height={p.h} rx={2} fill={n.color} />
              <text x={tx} y={cy - 2} style={{ fill: 'var(--text)', fontSize: 13, fontWeight: 600 }}>
                {n.label}
              </text>
              <text x={tx} y={cy + 13} style={{ fill: 'var(--text-2)', fontSize: 12 }}>
                {format(n)}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
