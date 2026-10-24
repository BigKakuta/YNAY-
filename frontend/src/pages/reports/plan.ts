// Planning calculators. Amounts here are whole currency units per month/year (not milliunits).

// ------------------------------------------------------------------ "What you could invest"

export type ProjectionPoint = { year: number; value: number; paidIn: number };

/**
 * Invest (income − base spending − things) every month for `years`, after first refilling a cash
 * buffer, compounding `ratePct` a year monthly.
 */
export function project(income: number, base: number, things: number, ratePct: number, buffer: number, start: number, years = 20) {
  const monthly = Math.max(0, income - base - things);
  const r = ratePct / 100 / 12;
  let value = start;
  let paidIn = start;
  let buf = buffer;
  const points: ProjectionPoint[] = [{ year: 0, value, paidIn }];
  for (let k = 1; k <= years * 12; k++) {
    let c = monthly;
    if (buf > 0) {
      const t = Math.min(buf, c);
      buf -= t;
      c -= t;
    }
    value = value * (1 + r) + c;
    paidIn += c;
    if (k % 12 === 0) points.push({ year: k / 12, value, paidIn });
  }
  return { monthly, points, bufferMonths: monthly > 0 ? Math.ceil(buffer / monthly) : Infinity };
}

// ------------------------------------------------------------------ early retirement (Germany, 2026 rules)

const RW = 42.52; // pension value per point, from July 2026
const AVG = 51_944; // average income → 1 pension point
const BBG_RV = 101_400; // pension contribution ceiling
const BBG_KV = 69_750; // health insurance contribution ceiling
const MINBASE = 1318.33 * 12; // minimum assessment base, voluntary public health insurance
const KV_FREI = 0.211; // voluntary insurance incl. care (childless)
const KV_RENT = 0.1295; // pensioner's share on the pension
const ABG = 0.26375; // capital gains tax incl. solidarity surcharge
const TFS = 0.7; // 30% partial exemption for equity ETFs
const SPB = 1000; // saver's allowance
const MINI = 603 * 12;
const MIDI = 2000 * 12;
const EMP_SV = 0.2175; // employee social contributions

/** Income tax, §32a EStG 2026. */
export function incomeTax(income: number): number {
  const x = Math.floor(income);
  if (x <= 12_348) return 0;
  if (x <= 17_799) {
    const y = (x - 12_348) / 1e4;
    return Math.floor((914.51 * y + 1400) * y);
  }
  if (x <= 69_878) {
    const z = (x - 17_799) / 1e4;
    return Math.floor((173.1 * z + 2397) * z + 1034.87);
  }
  if (x <= 277_825) return Math.floor(0.42 * x - 11_135.63);
  return Math.floor(0.45 * x - 19_470.38);
}

export type FireInput = {
  invest: number; // per month
  spend: number; // per month in retirement, before health insurance and tax
  returnPct: number;
  inflationPct: number;
  wageGrowthPct: number; // above inflation
  raiseSharePct: number; // share of each raise invested
  age: number;
  start: number; // invested today
  gross: number; // salary per year
  points: number; // pension points so far
  endAge: number; // money must last until
  dragPct: number; // tax drag while saving
  guenstiger: boolean; // personal rate on gains when lower
  partTime: boolean;
  hours: number;
  wage: number; // hourly
  netPay: number; // average net pay per month today
};

export const FIRE_DEFAULTS: FireInput = {
  invest: 500,
  spend: 2000,
  returnPct: 6,
  inflationPct: 2,
  wageGrowthPct: 0,
  raiseSharePct: 50,
  age: 30,
  start: 0,
  gross: 0,
  points: 0,
  endAge: 95,
  dragPct: 0.2,
  guenstiger: true,
  partTime: false,
  hours: 20,
  wage: 13.9,
  netPay: 0,
};

const growth = (o: FireInput, age: number) => Math.pow(1 + o.wageGrowthPct / 100, Math.max(0, age - o.age));
export const partTimeWage = (o: FireInput, age = o.age) => (o.partTime ? o.hours * 52 * o.wage * growth(o, age) : 0);
const contribution = (o: FireInput, age: number) => o.invest * 12 + (o.raiseSharePct / 100) * o.netPay * 12 * (growth(o, age) - 1);

export function pensionPoints(o: FireInput, stopAge: number) {
  let pts = o.points;
  for (let a = o.age; a < stopAge; a++) pts += Math.min(BBG_RV, o.gross * growth(o, a)) / AVG;
  if (o.partTime)
    for (let a = stopAge; a < 67; a++) {
      const w = partTimeWage(o, a);
      if (w > MINI) pts += Math.min(BBG_RV, w) / AVG;
    }
  return pts;
}

/** Employee social contributions on a part-time wage (minijob: none; midijob: reduced). */
export function partTimeContributions(g: number) {
  if (g <= MINI) return 0;
  if (g <= MIDI) return (MIDI / (MIDI - MINI)) * (g - MINI) * EMP_SV;
  return Math.min(g, BBG_KV) * EMP_SV;
}
export const isMinijob = (yearly: number) => yearly <= MINI;
export const isMidijob = (yearly: number) => yearly > MINI && yearly <= MIDI;
export const POINT_INCOME = AVG;

export type RetireYear = { withdrawal: number; health: number; tax: number; pension: number; wage: number };

function retireYear(V: number, basis: number, age: number, stopAge: number, o: FireInput, allowance: number): RetireYear {
  const pts = pensionPoints(o, stopAge);
  const pension = age >= 67 ? pts * RW * 12 : 0;
  const wage = age < 67 ? partTimeWage(o, age) : 0;
  const sv = partTimeContributions(wage);
  const insured = wage > MINI;
  const gainShare = V > 0 ? Math.max(0, 1 - basis / V) : 0;
  let W = Math.max(0, o.spend * 12 - pension - wage);
  let health = 0;
  let tax = 0;
  for (let it = 0; it < 6; it++) {
    const capInc = W * gainShare * TFS;
    let kvCap: number;
    let kvPen = 0;
    if (age < 67 && insured) {
      kvCap = 0;
      kvPen = sv;
    } else if (age < 67) {
      kvCap = Math.min(BBG_KV, Math.max(MINBASE, capInc + wage)) * KV_FREI;
    } else {
      kvPen = Math.min(BBG_KV, pension) * KV_RENT;
      kvCap = Math.max(0, Math.min(BBG_KV - pension, capInc)) * KV_FREI;
    }
    health = kvCap + kvPen;
    const other = age < 67 ? Math.max(0, wage - sv - 1230) : Math.max(0, pension - kvPen - 102);
    const taxOther = incomeTax(other);
    const taxableGain = Math.max(0, capInc - allowance);
    let taxCap = ABG * taxableGain;
    if (o.guenstiger) taxCap = Math.min(taxCap, incomeTax(other + taxableGain) - taxOther);
    tax = taxOther + taxCap;
    W = Math.max(0, o.spend * 12 + health + tax - pension - wage);
  }
  return { withdrawal: W, health, tax, pension, wage };
}

export type FireRun = {
  points: { age: number; value: number; pension: number }[];
  depletedAt: number | null;
  first: (RetireYear & { value: number }) | null;
  at67: (RetireYear & { value: number }) | null;
};

/** Portfolio in today's money, year by year, when stopping work at `stopAge`. */
export function simulate(o: FireInput, stopAge: number): FireRun {
  const real = (1 + o.returnPct / 100 - o.dragPct / 100) / (1 + o.inflationPct / 100) - 1;
  let V = o.start;
  let B = o.start;
  let allowance = SPB;
  const points: FireRun['points'] = [];
  let depletedAt: number | null = null;
  let first: FireRun['first'] = null;
  let at67: FireRun['at67'] = null;
  for (let age = o.age; age <= o.endAge; age++) {
    if (age < stopAge) {
      const c = contribution(o, age);
      V = V * (1 + real) + c;
      B = B / (1 + o.inflationPct / 100) + c;
      points.push({ age, value: V, pension: 0 });
    } else {
      const y = retireYear(V, B, age, stopAge, o, allowance);
      if (age === stopAge) first = { ...y, value: V };
      if (age === Math.max(67, stopAge)) at67 = { ...y, value: V };
      if (V < y.withdrawal && depletedAt === null) depletedAt = age;
      const share = V > 0 ? Math.min(1, y.withdrawal / V) : 1;
      B = (B * (1 - share)) / (1 + o.inflationPct / 100);
      V = Math.max(0, V - y.withdrawal) * (1 + real);
      points.push({ age, value: V, pension: y.pension });
    }
    allowance /= 1 + o.inflationPct / 100;
  }
  return { points, depletedAt, first, at67 };
}

/** The earliest age (up to 67) at which the money lasts until endAge, with its run. */
export function earliestStop(o: FireInput) {
  for (let a = o.age; a <= 67; a++) {
    const run = simulate(o, a);
    if (run.depletedAt === null) return { stopAge: a, run, found: true };
  }
  return { stopAge: 67, run: simulate(o, 67), found: false };
}

export const monthlyPension = (o: FireInput, stopAge: number) => pensionPoints(o, stopAge) * RW;
export const contributionAt = (o: FireInput, age: number) => contribution(o, age) / 12;
