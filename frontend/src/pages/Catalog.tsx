import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { searchEndpoints, getRepository, deleteRepository, rescanRepository } from '../api/client'
import type { SearchResultItem, EndpointDetail } from '../api/client'
import { EndpointModal } from '../components/EndpointModal'
import { EndpointCard } from '../components/EndpointCard'
import { groupByCapability, getGroupIcon } from '../utils/groupEndpoints'
import { endpointLabel, toBasicDetail } from '../utils/humanReadable'

const METHODS = ['', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e', POST: '#2563eb', PUT: '#f59e0b', DELETE: '#ef4444', PATCH: '#8b5cf6',
}

function useDebounce<T>(value: T, delay: number): T {
  const [deb, setDeb] = useState(value)
  useEffect(() => { const t = setTimeout(() => setDeb(value), delay); return () => clearTimeout(t) }, [value, delay])
  return deb
}

export default function Catalog() {
  const navigate = useNavigate()

  const [allEndpoints,   setAllEndpoints]   = useState<SearchResultItem[]>([])
  const [loadingAll,     setLoadingAll]     = useState(true)
  const [epDetails,      setEpDetails]      = useState<Map<number, EndpointDetail>>(new Map())
  const [detailsLoading, setDetailsLoading] = useState<Set<number>>(new Set())
  const [openEp,         setOpenEp]         = useState<SearchResultItem | null>(null)
  const modalSr = openEp
  const setModalSr = setOpenEp
  const [viewModes,      setViewModes]      = useState<Record<string, 'cards' | 'table'>>({})
  const [actionLoading,  setActionLoading]  = useState<number | null>(null)

  const [fp, setFp] = useState('')
  const [fm, setFm] = useState('')
  const [ff, setFf] = useState('')
  const [fr, setFr] = useState('')
  const [searchResults, setSearchResults] = useState<SearchResultItem[] | null>(null)
  const [searching,     setSearching]     = useState(false)
  const [error,         setError]         = useState<string | null>(null)

  const getVM = (g: string) => viewModes[g] ?? 'cards'
  const setVM = (g: string, m: 'cards' | 'table') => setViewModes(p => ({ ...p, [g]: m }))

  const dbPath = useDebounce(fp, 350)
  const dbFw   = useDebounce(ff, 350)
  const dbRepo = useDebounce(fr, 350)
  const isFiltering = !!(dbPath || fm || dbFw || dbRepo)

  const loadAll = () =>
    searchEndpoints({}).then(setAllEndpoints).catch(e => setError(e.message)).finally(() => setLoadingAll(false))

  useEffect(() => { loadAll() }, [])

  useEffect(() => {
    if (!isFiltering) { setSearchResults(null); return }
    setSearching(true)
    searchEndpoints({ path: dbPath || undefined, method: fm || undefined, framework: dbFw || undefined, repo: dbRepo || undefined })
      .then(setSearchResults).catch(e => setError(e.message)).finally(() => setSearching(false))
  }, [dbPath, fm, dbFw, dbRepo, isFiltering])

  const frameworks   = Array.from(new Set(allEndpoints.map(e => e.framework))).sort()
  const displayItems = isFiltering ? (searchResults ?? []) : allEndpoints
  const groups       = groupByCapability(displayItems)

  const clearFilters = () => { setFp(''); setFm(''); setFf(''); setFr(''); setSearchResults(null) }

  const toggleCard = async (sr: SearchResultItem) => {
    const id = sr.endpointId
    setModalSr(sr)
    if (!epDetails.has(id)) {
      setDetailsLoading(prev => new Set(prev).add(id))
      try {
        const rd = await getRepository(sr.repositoryId)
        const ep = rd.endpoints.find(e => e.id === id)
        if (ep) setEpDetails(prev => new Map(prev).set(id, ep))
      } catch {}
      finally { setDetailsLoading(prev => { const s = new Set(prev); s.delete(id); return s }) }
    }
  }

  const closeModal = () => setModalSr(null)

  const handleDelete = async (repoId: number) => {
    setActionLoading(repoId)
    try {
      await deleteRepository(repoId)
      setAllEndpoints(prev => prev.filter(ep => ep.repositoryId !== repoId))
      setSearchResults(prev => prev ? prev.filter(ep => ep.repositoryId !== repoId) : null)
    } catch { setError('Delete failed') }
    finally { setActionLoading(null) }
  }

  const handleRescan = async (repoId: number) => {
    setActionLoading(repoId)
    try {
      await rescanRepository(repoId)
      const updated = await searchEndpoints({})
      setAllEndpoints(updated)
      if (isFiltering) {
        const filtered = await searchEndpoints({ path: dbPath || undefined, method: fm || undefined, framework: dbFw || undefined, repo: dbRepo || undefined })
        setSearchResults(filtered)
      }
    } catch { setError('Re-scan failed') }
    finally { setActionLoading(null) }
  }

  return (
    <div className="page">
      <h1>API Catalog</h1>
      <p className="subtitle">All API endpoints grouped by business capability across all repositories.</p>

      <div className="card search-bar">
        <div className="search-grid">
          <div className="form-group-sm">
            <label>Endpoint path</label>
            <input className="input" placeholder="/users, /api, …" value={fp} onChange={e => setFp(e.target.value)} />
          </div>
          <div className="form-group-sm">
            <label>Method</label>
            <select className="input" value={fm} onChange={e => setFm(e.target.value)}>
              {METHODS.map(m => <option key={m} value={m}>{m || 'All'}</option>)}
            </select>
          </div>
          <div className="form-group-sm">
            <label>Framework</label>
            <select className="input" value={ff} onChange={e => setFf(e.target.value)}>
              <option value="">All</option>
              {frameworks.map(f => <option key={f} value={f}>{f}</option>)}
            </select>
          </div>
          <div className="form-group-sm">
            <label>Repository</label>
            <input className="input" placeholder="repo name…" value={fr} onChange={e => setFr(e.target.value)} />
          </div>
        </div>
        {isFiltering && <button className="btn-xs btn-gray clear-btn" onClick={clearFilters}>Clear ✕</button>}
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {(loadingAll || searching) && <div className="alert alert-info">{searching ? 'Searching…' : 'Loading endpoints…'}</div>}

      {!loadingAll && !searching && displayItems.length === 0 && (
        <div className="card placeholder">
          <p>
            {isFiltering ? 'No endpoints match the current filters.' : 'No APIs saved yet.'}
            {!isFiltering && <> <a href="/review">Submit a repository →</a></>}
          </p>
        </div>
      )}

      {!loadingAll && displayItems.length > 0 && Array.from(groups.entries()).map(([groupName, items]) => (
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
              {items.map(sr => {
                const detail = epDetails.get(sr.endpointId) ?? toBasicDetail(sr)
                return (
                  <EndpointCard
                    key={sr.endpointId}
                    ep={detail}
                    repoId={sr.repositoryId}
                    repoName={sr.repositoryName}
                    repoFramework={sr.framework}
                    isOpen={modalSr?.endpointId === sr.endpointId}
                    isLoadingDetails={detailsLoading.has(sr.endpointId)}
                    isActionLoading={actionLoading === sr.repositoryId}
                    onToggle={() => toggleCard(sr)}
                    onViewRepo={id => navigate(`/catalog/repo/${id}`)}
                    onDeleteRepo={handleDelete}
                    onRescanRepo={handleRescan}
                  />
                )
              })}
            </div>
          ) : (
            <table className="api-table">
              <thead>
                <tr><th>Method</th><th>Path</th><th>Repository</th><th>Handler</th></tr>
              </thead>
              <tbody>
                {items.map(sr => (
                  <tr key={sr.endpointId} style={{ cursor: 'pointer' }} onClick={() => toggleCard(sr)}>
                    <td><span className="method-badge" style={{ background: METHOD_COLORS[sr.method] ?? '#64748b' }}>{sr.method}</span></td>
                    <td>
                      <span className="mono">{sr.path}</span>
                      {endpointLabel(sr) && <div className="endpoint-label">{endpointLabel(sr)}</div>}
                    </td>
                    <td>
                      <span className="repo-name-inline"
                        style={{ cursor: 'pointer', color: '#2563eb' }}
                        onClick={e => { e.stopPropagation(); navigate(`/catalog/repo/${sr.repositoryId}`) }}>
                        {sr.repositoryName}
                      </span>
                      <span className="badge badge-green" style={{ marginLeft: '0.4rem', fontSize: '0.68rem' }}>{sr.framework}</span>
                    </td>
                    <td>{sr.handler ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      ))}

      {/* ── Endpoint detail modal ────────────────── */}
      {modalSr && (() => {
        const detail = epDetails.get(modalSr.endpointId)
        const loading = detailsLoading.has(modalSr.endpointId)
        return (
          <EndpointModal
            method={detail?.method ?? modalSr.method}
            path={detail?.path ?? modalSr.path}
            handler={detail?.handler}
            description={detail?.description}
            parameters={detail?.parameters}
            requestBodyType={detail?.requestBodyType}
            requestBodyFields={detail?.requestBodyFields}
            responseBodyType={detail?.responseBodyType}
            responseBodyFields={detail?.responseBodyFields}
            statusCodes={detail?.statusCodes}
            tags={detail?.tags}
            sourceFile={detail?.sourceFile}
            sourceLine={detail?.sourceLine}
            badge={modalSr.repositoryName}
            loading={loading}
            onClose={closeModal}
          />
        )
      })()}
    </div>
  )
}
