import { useState, useMemo } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { saveRepository } from '../api/client'
import type { SubmitResponse, ExtractedApi } from '../api/client'
import Breadcrumbs from '../components/Breadcrumbs'

const METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e', POST: '#2563eb', PUT: '#f59e0b', DELETE: '#ef4444', PATCH: '#8b5cf6',
}

function MethodBadge({ method }: { method: string }) {
  return (
    <span className="method-badge" style={{ background: METHOD_COLORS[method] ?? '#64748b' }}>
      {method}
    </span>
  )
}

function StatusChip({ ep }: { ep: ExtractedApi }) {
  if (ep.manuallyEdited) return <span className="chip chip-green">Reviewed</span>
  if (ep.needsReview)    return <span className="chip chip-yellow">Needs Review</span>
  if (ep.aiGenerated)    return <span className="chip chip-gray">AI Generated</span>
  return null
}

function rowClass(ep: ExtractedApi) {
  if (ep.manuallyEdited) return 'row-reviewed'
  if (ep.needsReview)    return 'row-needs-review'
  return 'row-ai-generated'
}

type Filter = 'all' | 'review' | 'ai' | 'done'

export default function Review() {
  const location = useLocation()
  const navigate = useNavigate()
  const result = (location.state as { result?: SubmitResponse } | null)?.result

  const [apis, setApis] = useState<ExtractedApi[]>(() => result?.apis.map(a => ({ ...a })) ?? [])
  const [editingIdx, setEditingIdx] = useState<number | null>(null)
  const [buf, setBuf] = useState<ExtractedApi | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<Filter>('all')

  const stats = useMemo(() => ({
    all: apis.length,
    review: apis.filter(a => !a.manuallyEdited && a.needsReview).length,
    ai: apis.filter(a => !a.manuallyEdited && a.aiGenerated && !a.needsReview).length,
    done: apis.filter(a => a.manuallyEdited).length,
  }), [apis])

  const displayedRows = useMemo(() =>
    apis.map((a, i) => [i, a] as [number, ExtractedApi]).filter(([, a]) => {
      if (filter === 'review') return !a.manuallyEdited && a.needsReview
      if (filter === 'ai')     return !a.manuallyEdited && a.aiGenerated && !a.needsReview
      if (filter === 'done')   return a.manuallyEdited
      return true
    }),
    [apis, filter]
  )

  if (!result) {
    return (
      <div className="page">
        <div className="alert alert-warning">
          No extraction result. <a href="/" onClick={e => { e.preventDefault(); navigate('/') }}>Go to Catalog →</a>
        </div>
      </div>
    )
  }

  const startEdit = (i: number) => { setEditingIdx(i); setBuf({ ...apis[i] }) }
  const cancelEdit = () => { setEditingIdx(null); setBuf(null) }
  const changeFilter = (f: Filter) => { cancelEdit(); setFilter(f) }
  const applyEdit = () => {
    if (editingIdx === null || !buf) return
    const updated = [...apis]
    updated[editingIdx] = { ...buf, manuallyEdited: true }
    setApis(updated)
    setEditingIdx(null)
    setBuf(null)
  }
  const deleteRow = (i: number) => {
    setApis(prev => prev.filter((_, idx) => idx !== i))
    if (editingIdx === i) cancelEdit()
    else if (editingIdx !== null && editingIdx > i) setEditingIdx(editingIdx - 1)
  }

  const field = (key: keyof ExtractedApi) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setBuf(prev => prev ? { ...prev, [key]: e.target.value || null } : prev)

  const handlePublish = async () => {
    setSaving(true)
    setError(null)
    try {
      const saved = await saveRepository({
        url: result.url,
        hostUrl: result.hostUrl,
        name: result.name,
        framework: result.framework,
        apis,
        commitSha: result.commitSha ?? null,
      })
      navigate(`/repositories/${saved.id}`)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Save failed')
      setSaving(false)
    }
  }

  return (
    <div className="page">
      <Breadcrumbs repoName={result.name} step="Review" />

      <div className="page-header">
        <div>
          <h1>{result.name}</h1>
          <p className="subtitle" style={{ marginBottom: 0 }}>
            {apis.length} endpoint{apis.length !== 1 ? 's' : ''} &mdash; {result.framework}
          </p>
        </div>
        <button
          className="btn btn-primary btn-lg"
          onClick={handlePublish}
          disabled={saving || apis.length === 0}
        >
          {saving ? 'Publishing…' : 'Publish documentation'}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="review-stats-bar">
        <button className={`rsb-chip${filter === 'all' ? ' rsb-chip--active' : ''}`} onClick={() => changeFilter('all')}>
          All <span className="rsb-count">{stats.all}</span>
        </button>
        {stats.review > 0 && (
          <button className={`rsb-chip${filter === 'review' ? ' rsb-chip--active rsb-chip--review-active' : ''}`} onClick={() => changeFilter('review')}>
            Needs review <span className="rsb-count rsb-count--yellow">{stats.review}</span>
          </button>
        )}
        {stats.ai > 0 && (
          <button className={`rsb-chip${filter === 'ai' ? ' rsb-chip--active' : ''}`} onClick={() => changeFilter('ai')}>
            AI generated <span className="rsb-count">{stats.ai}</span>
          </button>
        )}
        {stats.done > 0 && (
          <button className={`rsb-chip${filter === 'done' ? ' rsb-chip--active rsb-chip--done-active' : ''}`} onClick={() => changeFilter('done')}>
            Reviewed <span className="rsb-count rsb-count--green">{stats.done}</span>
          </button>
        )}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table className="review-table">
          <thead>
            <tr>
              <th style={{ width: '36%' }}>Endpoint</th>
              <th>Summary / Description</th>
              <th style={{ width: '90px' }}>Params</th>
              <th style={{ width: '120px' }}>Status</th>
              <th style={{ width: '52px' }}></th>
            </tr>
          </thead>
          <tbody>
            {displayedRows.map(([origIdx, ep]) =>
              editingIdx === origIdx && buf ? (
                <tr key={origIdx} className="editing-row" style={{ cursor: 'default' }}>
                  <td colSpan={5}>
                    <div style={{ padding: '0.65rem', display: 'flex', flexDirection: 'column', gap: '0.6rem' }}>
                      <div style={{ display: 'flex', gap: '0.5rem' }}>
                        <select
                          className="input-sm"
                          style={{ width: 90, flexShrink: 0 }}
                          value={buf.method}
                          onChange={field('method')}
                        >
                          {METHODS.map(m => <option key={m}>{m}</option>)}
                        </select>
                        <input
                          autoFocus
                          className="input-sm mono"
                          style={{ flex: 1 }}
                          value={buf.path ?? ''}
                          onChange={field('path')}
                          placeholder="/path"
                        />
                      </div>
                      <input
                        className="input-sm"
                        value={buf.summary ?? ''}
                        onChange={field('summary')}
                        placeholder="Summary (2–5 words, action-oriented)"
                      />
                      <textarea
                        className="input-sm"
                        value={buf.description ?? ''}
                        onChange={field('description')}
                        placeholder="Description (1–2 sentences)"
                        rows={2}
                        style={{ resize: 'vertical' }}
                      />
                      <div style={{ display: 'flex', gap: '0.35rem', justifyContent: 'flex-end' }}>
                        <button className="btn-xs btn-red" onClick={() => deleteRow(origIdx)}>Delete</button>
                        <button className="btn-xs btn-gray" onClick={cancelEdit}>Cancel</button>
                        <button className="btn-xs btn-green" onClick={applyEdit}>Apply</button>
                      </div>
                    </div>
                  </td>
                </tr>
              ) : (
                <tr key={origIdx} className={`${rowClass(ep)} review-row`} onClick={() => startEdit(origIdx)}>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <MethodBadge method={ep.method} />
                      <div>
                        <div className="mono" style={{ fontSize: '0.88rem' }}>{ep.path}</div>
                        {(ep.handler || ep.controller) && (
                          <div className="review-sub">
                            {ep.controller ? `${ep.controller}` : ''}{ep.controller && ep.handler ? ' · ' : ''}{ep.handler ?? ''}
                          </div>
                        )}
                      </div>
                    </div>
                  </td>
                  <td style={{ color: 'var(--text-secondary)', fontSize: '0.88rem' }}>
                    {ep.summary || ep.description || <span style={{ color: 'var(--text-muted)' }}>—</span>}
                    {ep.sourceFile && (
                      <div className="review-sub review-sub--file">{ep.sourceFile}{ep.sourceLine ? `:${ep.sourceLine}` : ''}</div>
                    )}
                  </td>
                  <td>
                    {ep.parameters && ep.parameters.length > 0 && (
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.2rem' }}>
                        {['PATH','QUERY','BODY'].map(loc => {
                          const count = ep.parameters!.filter(p => p.location === loc).length
                          return count > 0 ? (
                            <span key={loc} className="param-loc-chip" data-loc={loc.toLowerCase()}>
                              {count} {loc.toLowerCase()}
                            </span>
                          ) : null
                        })}
                      </div>
                    )}
                  </td>
                  <td><StatusChip ep={ep} /></td>
                  <td>
                    <button
                      className="btn-xs btn-gray"
                      onClick={e => { e.stopPropagation(); startEdit(origIdx) }}
                    >
                      Edit
                    </button>
                  </td>
                </tr>
              )
            )}
            {displayedRows.length === 0 && (
              <tr>
                <td colSpan={5} style={{ textAlign: 'center', padding: '1.5rem', color: 'var(--text-muted)', fontSize: '0.88rem' }}>
                  No endpoints in this view
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

