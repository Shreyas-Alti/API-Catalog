import { useState, useEffect } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { getRepositories, deleteRepository, rescanRepository } from '../api/client'
import type { RepositorySummary, SubmitResponse } from '../api/client'
import AddRepoDialog from '../components/AddRepoDialog'

const FW_COLOR: Record<string, string> = {
  'FastAPI': '#009688', 'Spring Boot': '#6db33f', 'Express': '#cbca3e',
  'NestJS': '#e0234e', 'Django': '#44b78b', 'Flask': '#8a8a8a',
  'Gin': '#00acd7', 'Echo': '#00acc1', 'Fiber': '#00b4d8',
  'Fastify': '#999', 'Koa': '#33a0ff', 'JAX-RS': '#ff6f00', 'ASP.NET': '#512bd4',
}

function SkeletonCards() {
  return (
    <div className="repo-grid" aria-hidden="true">
      {[0, 1, 2].map(i => (
        <div key={i} className="repo-grid-card skeleton-card">
          <div className="sk sk-name" />
          <div className="sk sk-url" />
          <div className="sk sk-meta" />
          <div className="sk sk-actions" />
        </div>
      ))}
    </div>
  )
}

function EmptyState({ onAdd }: { onAdd: () => void }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 16V8a2 2 0 00-1-1.73l-7-4a2 2 0 00-2 0l-7 4A2 2 0 003 8v8a2 2 0 001 1.73l7 4a2 2 0 002 0l7-4A2 2 0 0021 16z"/>
          <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
          <line x1="12" y1="22.08" x2="12" y2="12"/>
        </svg>
      </div>
      <h2>No repositories yet</h2>
      <p>Paste a GitHub URL to extract and document your APIs automatically.</p>
      <div className="empty-steps">
        <div className="empty-step"><span className="es-num">1</span><span>Paste a GitHub URL</span></div>
        <span className="es-arrow">→</span>
        <div className="empty-step"><span className="es-num">2</span><span>Review endpoints</span></div>
        <span className="es-arrow">→</span>
        <div className="empty-step"><span className="es-num">3</span><span>Browse API docs</span></div>
      </div>
      <button className="btn btn-primary" onClick={onAdd}>Add repository</button>
    </div>
  )
}

export default function Catalog() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [repos, setRepos] = useState<RepositorySummary[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [showAdd, setShowAdd] = useState(false)
  const [actionLoading, setAL] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getRepositories()
      .then(setRepos)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (searchParams.get('add') === '1') {
      setShowAdd(true)
      setSearchParams({}, { replace: true })
    }
  }, [searchParams, setSearchParams])

  const filtered = search.trim()
    ? repos.filter(r =>
        r.name.toLowerCase().includes(search.toLowerCase()) ||
        r.url.toLowerCase().includes(search.toLowerCase()) ||
        (r.framework ?? '').toLowerCase().includes(search.toLowerCase())
      )
    : repos

  const handleDelete = async (r: RepositorySummary, e: React.MouseEvent) => {
    e.stopPropagation()
    e.preventDefault()
    if (!window.confirm(`Delete "${r.name}" and all its endpoints?`)) return
    setAL(r.id)
    try { await deleteRepository(r.id); setRepos(prev => prev.filter(x => x.id !== r.id)) }
    catch { setError('Delete failed') }
    finally { setAL(null) }
  }

  const handleRescan = async (r: RepositorySummary, e: React.MouseEvent) => {
    e.stopPropagation()
    e.preventDefault()
    setAL(r.id)
    try { await rescanRepository(r.id); setRepos(await getRepositories()) }
    catch { setError('Rescan failed') }
    finally { setAL(null) }
  }

  const onSubmitted = (result: SubmitResponse) => {
    setShowAdd(false)
    navigate('/review', { state: { result } })
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>API Catalog</h1>
          {!loading && repos.length > 0 && (
            <p className="subtitle" style={{ marginBottom: 0 }}>
              {repos.length} repositor{repos.length !== 1 ? 'ies' : 'y'}
            </p>
          )}
        </div>
        <button className="btn btn-primary" onClick={() => setShowAdd(true)}>
          + Add repository
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {!loading && repos.length > 0 && (
        <div className="search-wrapper">
          <svg className="search-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path fillRule="evenodd" d="M8 4a4 4 0 100 8 4 4 0 000-8zM2 8a6 6 0 1110.89 3.476l4.817 4.817a1 1 0 01-1.414 1.414l-4.816-4.816A6 6 0 012 8z" clipRule="evenodd" />
          </svg>
          <input
            className="search-input search-input--icon"
            placeholder="Search by name, URL, or framework…"
            value={search}
            onChange={e => setSearch(e.target.value)}
            autoComplete="off"
          />
          {search && (
            <button className="search-clear" onClick={() => setSearch('')} aria-label="Clear search">×</button>
          )}
        </div>
      )}

      {!loading && repos.length > 0 && search && (
        <p className="search-count">
          {filtered.length === 0
            ? `No results for "${search}"`
            : `${filtered.length} of ${repos.length} repositor${repos.length !== 1 ? 'ies' : 'y'}`}
        </p>
      )}

      {loading && <SkeletonCards />}

      {!loading && repos.length === 0 && <EmptyState onAdd={() => setShowAdd(true)} />}

      {!loading && filtered.length === 0 && repos.length > 0 && (
        <div className="card placeholder">
          <p>No repositories match &ldquo;{search}&rdquo;.</p>
        </div>
      )}

      {!loading && filtered.length > 0 && (
        <div className="repo-grid">
          {filtered.map(r => (
            <Link
              key={r.id}
              to={`/repositories/${r.id}`}
              className="repo-grid-card"
              style={{ borderLeftColor: FW_COLOR[r.framework] ?? 'var(--border)', borderLeftWidth: '3px' }}
            >
              <div className="rgc-header">
                <span className="rgc-name">{r.name}</span>
                <span className="meta-pill">{r.framework}</span>
              </div>
              <div className="rgc-url">{r.url.replace('https://github.com/', '')}</div>
              <div className="rgc-meta">
                <span className="meta-pill">{r.endpointCount} endpoint{r.endpointCount !== 1 ? 's' : ''}</span>
                <span className="meta-pill">{new Date(r.createdAt).toLocaleDateString()}</span>
              </div>
              <div className="rgc-actions" onClick={e => { e.stopPropagation(); e.preventDefault() }}>
                <button className="btn-xs btn-gray" disabled={actionLoading === r.id} onClick={e => handleRescan(r, e)}>
                  {actionLoading === r.id ? '…' : '↺ Rescan'}
                </button>
                <button className="btn-xs btn-red" disabled={actionLoading === r.id} onClick={e => handleDelete(r, e)}>
                  Delete
                </button>
              </div>
            </Link>
          ))}
        </div>
      )}

      {showAdd && <AddRepoDialog onClose={() => setShowAdd(false)} onSubmitted={onSubmitted} />}
    </div>
  )
}

