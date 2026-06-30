type QueryKey = string | readonly unknown[]

interface QueryEntry<T = unknown> {
  data: T
  updatedAt: number
  staleMs: number
}

interface QueryFetchOptions {
  staleMs?: number
  force?: boolean
}

const DEFAULT_STALE_MS = 30_000
const DEFAULT_MAX_ENTRIES = 500

function stableStringify(value: unknown): string {
  if (value === undefined) return 'undefined'
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  const obj = value as Record<string, unknown>
  return `{${Object.keys(obj).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(obj[key])}`).join(',')}}`
}

export function queryKey(key: QueryKey): string {
  return typeof key === 'string' ? key : stableStringify(key)
}

export function createListSignature<T>(
  items: readonly T[],
  getId: (item: T, index: number) => unknown,
) {
  const length = items.length
  if (!length) return '0'

  let hash = 2166136261
  let first = ''
  let last = ''

  for (let index = 0; index < length; index += 1) {
    const id = String(getId(items[index], index) ?? index)
    if (index === 0) first = id
    if (index === length - 1) last = id

    hash ^= id.length
    hash = Math.imul(hash, 16777619)
    for (let i = 0; i < id.length; i += 1) {
      hash ^= id.charCodeAt(i)
      hash = Math.imul(hash, 16777619)
    }
  }

  return `${length}|${first}|${last}|${(hash >>> 0).toString(36)}`
}

function matchesQueryKey(candidate: string, keyOrPrefix: QueryKey): boolean {
  const prefix = queryKey(keyOrPrefix)
  if (candidate === prefix) return true
  if (!Array.isArray(keyOrPrefix)) return candidate.startsWith(prefix)
  if (keyOrPrefix.length === 0) return true
  const arrayPrefix = `[${keyOrPrefix.map(stableStringify).join(',')},`
  return candidate.startsWith(arrayPrefix)
}

export class QueryCache {
  private entries = new Map<string, QueryEntry>()
  private inflight = new Map<string, Promise<unknown>>()
  private revision = 0
  private readonly maxEntries: number

  constructor(maxEntries = DEFAULT_MAX_ENTRIES) {
    this.maxEntries = Math.max(50, maxEntries)
  }

  get<T>(key: QueryKey): T | undefined {
    return this.entries.get(queryKey(key))?.data as T | undefined
  }

  getUpdatedAt(key: QueryKey): number {
    return this.entries.get(queryKey(key))?.updatedAt || 0
  }

  has(key: QueryKey): boolean {
    return this.entries.has(queryKey(key))
  }

  isFresh(key: QueryKey, staleMs?: number): boolean {
    const entry = this.entries.get(queryKey(key))
    if (!entry) return false
    const ttl = staleMs ?? entry.staleMs ?? DEFAULT_STALE_MS
    return Date.now() - entry.updatedAt < ttl
  }

  set<T>(key: QueryKey, data: T, staleMs = DEFAULT_STALE_MS): T {
    const normalized = queryKey(key)
    this.entries.delete(normalized)
    this.entries.set(normalized, { data, updatedAt: Date.now(), staleMs })
    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next().value
      if (oldest === undefined) break
      this.entries.delete(oldest)
    }
    return data
  }

  async fetch<T>(key: QueryKey, fetcher: () => Promise<T>, options: QueryFetchOptions = {}): Promise<T> {
    const normalized = queryKey(key)
    const staleMs = options.staleMs ?? DEFAULT_STALE_MS
    const entry = this.entries.get(normalized)
    if (!options.force && entry && Date.now() - entry.updatedAt < staleMs) {
      return entry.data as T
    }

    const running = this.inflight.get(normalized)
    if (running && !options.force) return running as Promise<T>
    if (running && options.force) {
      this.inflight.delete(normalized)
      this.revision += 1
    }

    const revisionAtStart = this.revision
    const task = fetcher()
      .then((data) => {
        if (revisionAtStart !== this.revision) return data
        return this.set(key, data, staleMs)
      })
      .finally(() => {
        if (this.inflight.get(normalized) === task) {
          this.inflight.delete(normalized)
        }
      })

    this.inflight.set(normalized, task)
    return task
  }

  invalidate(keyOrPrefix?: QueryKey): void {
    if (keyOrPrefix === undefined) {
      this.entries.clear()
      this.inflight.clear()
      this.revision += 1
      return
    }
    for (const key of Array.from(this.entries.keys())) {
      if (matchesQueryKey(key, keyOrPrefix)) {
        this.entries.delete(key)
      }
    }
    for (const key of Array.from(this.inflight.keys())) {
      if (matchesQueryKey(key, keyOrPrefix)) {
        this.inflight.delete(key)
      }
    }
    this.revision += 1
  }
}

export const appQueryCache = new QueryCache()
