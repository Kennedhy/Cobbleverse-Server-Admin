export async function request(path, { method = 'GET', body, signal } = {}) {
  let response;
  try {
    response = await fetch(path, {
      method,
      signal,
      credentials: 'same-origin',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Error('Não foi possível alcançar o painel do servidor.');
  }
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(payload.message || `Solicitação recusada (${response.status}).`);
    error.status = response.status;
    throw error;
  }
  return payload;
}

export function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

export function copy(value) {
  return JSON.parse(JSON.stringify(value));
}

export function newConfig(state) {
  return copy(state.pending || state.active);
}

export function parseList(value) {
  return [...new Set(value.split(',').map((part) => part.trim()).filter(Boolean))];
}

export function formatMultiplier(value) {
  return Number(value ?? 1).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
