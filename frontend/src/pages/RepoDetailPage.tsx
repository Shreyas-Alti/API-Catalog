import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getRepository, deleteRepository, rescanRepository } from '../api/client'
import type { RepositoryDetail } from '../api/client'
import { EndpointModal } from '../components/EndpointModal'
import { EndpointCard } from '../components/EndpointCard'
import { groupByCapability, getGroupIcon } from '../utils/groupEndpoints'
import { endpointLabel } from '../utils/humanReadable'

const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e', POST: '#2563eb', PUT: '#f59e0b', DELETE: '#ef4444', PATCH: '#8b5cf6',
}

export default function RepoDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [repo, setRepo]           = useState<RepositoryDetail | null>(null)
  const [loading, setLoading]     = useState(true)
  const [error, setError]         = useState<string | null>(null)
  const [actionLoading, setAL]    = useState(false)

  const [openEp, setOpenEp]       = useState<Set<number>>(new Set())
  const [viewModes, setVMs]       = useState<Record<string, 'cards' | 'table'>>({})

  const getVM = (g: string) => viewModes[g] ?? 'cards'
  const setVM = (g: string, m: 'cards' | 'table') => setVMs(p => ({ ...p, [g]: m }))

  const toggleEp = (epId: number) =>
    setOpenEp(prev => prev === epId ? null : epId)

  useEffect(() => {
    if (!id) return
    getRepository(parseInt(id))
      .then(setRepo)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [id])

  const handleDelete = async () => {
    if (!repo || !window.confirm(`Delete "${repo.name}" and all its endpoints?`)) return
    setAL(true)
    try { await deleteRepository(repo.id); navigate('/catalog') }
    catch { setError('Delete failed') }
    finally { setAL(false) }
  }

  const handleRescan = async () => {
    if (!repo) return
    setAL(true)
    try { setRepo(await rescanRepository(repo.id)) }
    catch { setError('Re-scan failed') }
    finally { setAL(false) }
  }

  if (loading) return <div className="page"><div className="alert alert-info">Loading…</div></div>
  if (error)   return <div className="page"><div className="alert alert-error">{error}</div></div>
  if (!repo)   return null

  const groups = groupByCapability(repo.endpoints)

  return (
    <div className="page">
      {/* Back */}
      <button className="btn btn-outline" style={{ marginBottom: '1.25rem', padding: '0.35rem 1rem', fontSize: '0.85rem' }}
        onClick={() => navigate('/catalog')}>
        ← Back to Catalog
      </button>

      {/* Repo info card */}
      <div className="card" style={{ marginBottom: '1.5rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem', flexWrap: 'wrap' }}>
          <div>
            <h1 style={{ fontSize: '1.5rem', fontWeight: 700, color: '#0f172a', marginBottom: '0.3rem' }}>
              {repo.name}
            </h1>
            <a href={repo.url} target="_blank" rel="noopener noreferrer" className="url-link">{repo.url}</a>
            {repo.hostUrl && (
              <div className="host-url-row" style={{ marginTop: '0.25rem' }}>
                <span className="host-label">API Base URL:</span>
                <a href={repo.hostUrl} target="_blank" rel="noopener noreferrer" className="url-link">{repo.hostUrl}</a>
              </div>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
            <span className="badge badge-green">{repo.framework}</span>
            <span className="meta-pill">{repo.endpoints.length} endpoint{repo.endpoints.length !== 1 ? 's' : ''}</span>
            <span className="meta-pill">{new Date(repo.createdAt).toLocaleDateString()}</span>
          </div>
        </div>
        <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
          <button className="btn btn-primary" disabled={actionLoading} onClick={handleRescan}>
            {actionLoading ? 'Working…' : '↺ Re-scan'}
          </button>
          <button className="btn-xs btn-red" style={{ padding: '0.4rem 0.9rem', fontSize: '0.85rem' }}
            disabled={actionLoading} onClick={handleDelete}>
            🗑 Delete Repository
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {repo.endpoints.length === 0 ? (
        <div className="card placeholder"><p>No endpoints recorded. Try re-scanning.</p></div>
      ) : (
        Array.from(groups.entries()).map(([groupName, items]) => (
          <div key={groupName} className="capability-group-card">
            <div className="capability-group-header">
              <span className="capability-icon">{getGroupIcon(groupName)}</span>
              <span className="capability-name">{groupName}</span>
              <span className="capability-count">{items.length}</span>
              <div className="view-toggle" style={{ marginLeft: 'auto' }}>
                <button className={`vt-btn${getVM(groupName) === 'cards' ? ' vt-btn--active' : ''}`}
                  onClick={() => setVM(groupName, 'cards')}>⊞ Cards</button>
                <button className={`vt-btn${getVM(groupName) === 'table' ? ' vt-btn--active' : ''}`}
                  onClick={() => setVM(groupName, 'table')}>≡ Table</button>
              </div>
            </div>

            {getVM(groupName) === 'cards' ? (
              <div className="endpoint-cards-grid" style={{ padding: '0.75rem' }}>
                {items.map(ep => (
                  <EndpointCard
                    key={ep.id}
                    ep={ep}
                    isOpen={openEp === ep.id}
                    onToggle={() => toggleEp(ep.id)}
                  />
                ))}
              </div>
            ) : (
              <table className="api-table">
                <thead>
                  <tr><th></th><th>Method</th><th>Path</th><th>Handler</th><th>Description</th></tr>
                </thead>
                <tbody>
                  {items.map(ep => (
                    <tr key={ep.id} style={{ cursor: 'pointer' }} onClick={() => toggleEp(ep.id)}>
                      <td style={{ width: '1.5rem', color: 'var(--text-muted)', fontSize: '0.75rem' }}>
                        {openEp === ep.id ? '▾' : '▸'}
                      </td>
                      <td>
                        <span className="method-badge" style={{ background: METHOD_COLORS[ep.method] ?? '#64748b' }}>
                          {ep.method}
                        </span>
                      </td>
                      <td>
                        <span className="mono">{ep.path}</span>
                        {endpointLabel(ep) && <div className="endpoint-label">{endpointLabel(ep)}</div>}
                      </td>
                      <td>{ep.handler ?? '—'}</td>
                      <td>{ep.description ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ))
      )}

      {/* ── Endpoint detail modal ────────────────── */}
      {openEp !== null && (() => {
        const ep = repo.endpoints.find(e => e.id === openEp)
        if (!ep) return null
        return (
          <EndpointModal
            method={ep.method} path={ep.path}
            handler={ep.handler} description={ep.description}
            parameters={ep.parameters}
            requestBodyType={ep.requestBodyType} requestBodyFields={ep.requestBodyFields}
            responseBodyType={ep.responseBodyType} responseBodyFields={ep.responseBodyFields}
            statusCodes={ep.statusCodes} tags={ep.tags}
            sourceFile={ep.sourceFile} sourceLine={ep.sourceLine}
            onClose={() => setOpenEp(null)}
          />
        )
      })()}
    </div>
  )
}
