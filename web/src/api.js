function waitBeforeRetry(delay, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('A solicitação foi cancelada.', 'AbortError'));
      return;
    }
    const finish = () => {
      signal?.removeEventListener('abort', cancel);
      resolve();
    };
    const timer = setTimeout(finish, delay);
    const cancel = () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', cancel);
      reject(new DOMException('A solicitação foi cancelada.', 'AbortError'));
    };
    signal?.addEventListener('abort', cancel, { once: true });
  });
}

export async function request(path, { method = 'GET', body, signal, retryBusy = false } = {}) {
  const retries = retryBusy && method === 'GET' ? 2 : 0;
  for (let attempt = 0; ; attempt += 1) {
    let response;
    try {
      response = await fetch(path, {
        method,
        signal,
        credentials: 'same-origin',
        headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (failure) {
      if (failure.name === 'AbortError') throw failure;
      throw new Error('Não foi possível alcançar o painel do servidor.');
    }
    const payload = await response.json().catch(() => ({}));
    if (response.ok) return payload;

    const error = new Error(payload.message || `Solicitação recusada (${response.status}).`);
    error.status = response.status;
    if (response.status !== 503 || attempt >= retries) throw error;
    await waitBeforeRetry(350 * (attempt + 1), signal);
  }
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
