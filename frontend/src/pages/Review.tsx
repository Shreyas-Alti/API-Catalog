import { useState, useEffect } from 'react'
import type { FormEvent } from 'react'
import { submitRepository, saveRepository } from '../api/client'
import type { SubmitResponse, ExtractedApi } from '../api/client'

const METHODS = ['GET','POST','PUT','DELETE','PATCH']
const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e', POST: '#6366f1', PUT: '#f59e0b', DELETE: '#ef4444', PATCH: '#8b5cf6',
}

export default function Review() {
  const [url, setUrl] = useState('')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [savedId, setSavedId] = useState<number | null>(null)
  const [result, setResult] = useState<SubmitResponse | null>(null)
  const [editedApis, setEditedApis] = useState<ExtractedApi[]>([])
  const [editingIdx, setEditingIdx] = useState<number | null>(null)
  const [buf, setBuf] = useState<ExtractedApi | null>(null)

  useEffect(() => {
    if (result) setEditedApis(result.apis.map(a => ({ ...a })))
  }, [result])

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError(null)
    setResult(null)
    setSavedId(null)
    try {
      const data = await submitRepository(url)
      setResult(data)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Unknown error')
    } finally {
      setLoading(false)
    }
  }

  const startEdit = (i: number) => { setEditingIdx(i); setBuf({ ...editedApis[i] }) }
  const cancelEdit = () => { setEditingIdx(null); setBuf(null) }
  const applyEdit = () => {
    if (editingIdx === null || !buf) return
    const updated = [...editedApis]
    updated[editingIdx] = buf
    setEditedApis(updated)
    setEditingIdx(null)
    setBuf(null)
  }
  const deleteRow = (i: number) => {
    setEditedApis(editedApis.filter((_, idx) => idx !== i))
    if (editingIdx === i) { setEditingIdx(null); setBuf(null) }
  }

  const handleSave = async () => {
    if (!result) return
    setSaving(true)
    setError(null)
    try {
      const saved = await saveRepository({ url: result.url, name: result.name, framework: result.framework, apis: editedApis })
      setSavedId(saved.id)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Save failed')
    } finally {
      setSaving(false)
    }
  }

  const field = (key: keyof ExtractedApi) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setBuf(prev => prev ? { ...prev, [key]: e.target.value || null } : prev)

  return (
    <div className="page">
      <h1>Submit Repository</h1>
      <p className="subtitle">Enter a public Git repository URL to clone, detect its framework, and extract API endpoints.</p>

      <form className="card" onSubmit={handleSubmit}>
        <div className="form-group">
          <label htmlFor="url">Repository URL</label>
          <input id="url" type="url" className="input" placeholder="https://github.com/owner/repo"
            value={url} onChange={e => setUrl(e.target.value)} required disabled={loading} />
        </div>
        <button type="submit" className="btn btn-primary" disabled={loading || !url}>
          {loading ? 'Cloning & extracting…' : 'Extract APIs'}
        </button>
      </form>

      {error && <div className="alert alert-error">{error}</div>}
      {savedId && <div className="alert alert-success">Saved! <a href="/catalog">View in Catalog →</a></div>}

      {result && (
        <>
          <div className="card result-meta-card">
            <div className="result-meta">
              <div>
                <h2>{result.name}</h2>
                <a href={result.url} target="_blank" rel="noopener noreferrer" className="url-link">{result.url}</a>
              </div>
              <span className={`badge ${result.supported ? 'badge-green' : 'badge-yellow'}`}>{result.framework}</span>
            </div>
          </div>

          {!result.supported && <div className="alert alert-warning">Framework &ldquo;{result.framework}&rdquo; is not yet supported.</div>}
          {result.supported && editedApis.length === 0 && <div className="alert alert-info">No endpoints found. The parser may not recognise the routing patterns used.</div>}

          {editedApis.length > 0 && (
            <div className="card">
              <div className="table-header">
                <h2>{editedApis.length} endpoint{editedApis.length !== 1 ? 's' : ''}</h2>
                <button className="btn btn-primary" onClick={handleSave} disabled={saving || !!savedId}>
                  {saving ? 'Saving…' : savedId ? 'Saved ✓' : 'Save to Catalog'}
                </button>
              </div>
              <table className="api-table">
                <thead>
                  <tr><th>Method</th><th>Path</th><th>Controller</th><th>Handler</th><th>Actions</th></tr>
                </thead>
                <tbody>
                  {editedApis.map((api, i) => (
                    editingIdx === i && buf ? (
                      <>
                        <tr key={i} className="editing-row">
                          <td>
                            <select className="input-sm" value={buf.method} onChange={field('method')}>
                              {METHODS.map(m => <option key={m}>{m}</option>)}
                            </select>
                          </td>
                          <td><input className="input-sm mono" value={buf.path ?? ''} onChange={field('path')} /></td>
                          <td><input className="input-sm" value={buf.controller ?? ''} onChange={field('controller')} /></td>
                          <td><input className="input-sm" value={buf.handler ?? ''} onChange={field('handler')} /></td>
                          <td className="action-cell">
                            <button className="btn-xs btn-green" onClick={applyEdit}>Apply</button>
                            <button className="btn-xs btn-gray" onClick={cancelEdit}>Cancel</button>
                          </td>
                        </tr>
                        <tr key={`${i}-extra`} className="editing-extra">
                          <td colSpan={5}>
                            <div className="edit-extra-grid">
                              <div className="form-group-sm"><label>Description</label>
                                <textarea className="input-sm" rows={2} value={buf.description ?? ''} onChange={field('description')} /></div>
                              <div className="form-group-sm"><label>Request Body</label>
                                <textarea className="input-sm" rows={2} value={buf.requestBody ?? ''} onChange={field('requestBody')} /></div>
                              <div className="form-group-sm"><label>Response Body</label>
                                <textarea className="input-sm" rows={2} value={buf.responseBody ?? ''} onChange={field('responseBody')} /></div>
                            </div>
                          </td>
                        </tr>
                      </>
                    ) : (
                      <tr key={i}>
                        <td><span className="method-badge" style={{ background: METHOD_COLORS[api.method] ?? '#64748b' }}>{api.method}</span></td>
                        <td className="mono">{api.path}</td>
                        <td>{api.controller ?? '—'}</td>
                        <td>{api.handler ?? '—'}</td>
                        <td className="action-cell">
                          <button className="btn-xs btn-gray" onClick={() => startEdit(i)}>Edit</button>
                          <button className="btn-xs btn-red" onClick={() => deleteRow(i)}>✕</button>
                        </td>
                      </tr>
                    )
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  )
}