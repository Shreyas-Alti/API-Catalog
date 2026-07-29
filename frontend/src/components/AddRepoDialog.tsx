import { useState, useEffect } from 'react'
import { submitRepository } from '../api/client'
import type { SubmitResponse } from '../api/client'
import { EXTRACTION_STAGES } from '../constants/extractionStages'

interface Props {
  onClose: () => void
  onSubmitted: (result: SubmitResponse) => void
}

type Phase = 'form' | 'loading' | 'report'

function Spinner() {
  return (
    <div style={{
      width: 28, height: 28, borderRadius: '50%',
      border: '3px solid rgba(18,170,255,0.15)',
      borderTopColor: 'var(--accent)',
      animation: 'spin 0.7s linear infinite',
    }} />
  )
}

export default function AddRepoDialog({ onClose, onSubmitted }: Props) {
  const [phase, setPhase] = useState<Phase>('form')
  const [url, setUrl] = useState('')
  const [hostUrl, setHostUrl] = useState('')
  const [projectName, setProjectName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [stage, setStage] = useState(0)
  const [result, setResult] = useState<SubmitResponse | null>(null)

  // Cycle loading stage text while the POST is in flight
  useEffect(() => {
    if (phase !== 'loading') return
    const id = setInterval(() => setStage(s => Math.min(s + 1, EXTRACTION_STAGES.length - 1)), 1200)
    return () => clearInterval(id)
  }, [phase])

  // ESC to close (not while loading)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && phase !== 'loading') onClose() }
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => { document.removeEventListener('keydown', onKey); document.body.style.overflow = '' }
  }, [onClose, phase])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!url.trim()) return
    setError(null)
    setPhase('loading')
    setStage(0)
    try {
      const data = await submitRepository(url.trim(), hostUrl.trim() || null)
      setResult(data)
      setPhase('report')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Extraction failed')
      setPhase('form')
    }
  }

  function handleReview() {
    if (!result) return
    onSubmitted({ ...result, name: projectName.trim() || result.name })
  }

  return (
    <div
      className="modal-overlay"
      onClick={phase !== 'loading' ? onClose : undefined}
    >
      <div
        className="modal"
        style={{ maxWidth: 520, position: 'relative' }}
        onClick={e => e.stopPropagation()}
      >
        {/* ── FORM ─────────────────────────────────── */}
        {phase === 'form' && (
          <>
            {/* × pinned to top-right corner of the modal */}
            <button
              className="modal-close"
              onClick={onClose}
              style={{ position: 'absolute', top: '0.6rem', right: '0.6rem', zIndex: 1 }}
            >✕</button>

            <div className="modal-body" style={{ padding: '1.5rem 1.25rem 1.25rem' }}>
              <p style={{ fontWeight: 600, fontSize: '1rem', color: 'var(--accent)', marginBottom: '1rem' }}>
                Add repository
              </p>
              {error && <div className="alert alert-error">{error}</div>}
              <form onSubmit={handleSubmit}>
                <div className="form-group">
                  <label>Project name <span className="optional-label">(optional)</span></label>
                  <input
                    className="input"
                    placeholder="Defaults to repo name"
                    value={projectName} onChange={e => setProjectName(e.target.value)}
                    autoFocus
                  />
                </div>
                <div className="form-group">
                  <label>
                    API base URL{' '}
                    <span className="optional-label">(optional — enables Test Request)</span>
                  </label>
                  <input
                    className="input" type="url"
                    placeholder="https://api.myapp.com"
                    value={hostUrl} onChange={e => setHostUrl(e.target.value)}
                  />
                </div>
                <div className="form-group">
                  <label>Repository URL</label>
                  <input
                    className="input" type="url" required
                    placeholder="https://github.com/owner/repo"
                    value={url} onChange={e => setUrl(e.target.value)}
                  />
                </div>
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '0.25rem' }}>
                  <button type="submit" className="btn btn-primary" disabled={!url.trim()}>
                    Extract APIs
                  </button>
                </div>
              </form>
            </div>
          </>
        )}

        {/* ── LOADING ──────────────────────────────── */}
        {phase === 'loading' && (
          <div className="loading-stage" style={{ padding: '3.5rem 2rem' }}>
            <Spinner />
            <p style={{ fontSize: '1rem', color: 'var(--text-primary)', fontWeight: 500 }}>
              {EXTRACTION_STAGES[stage]}
            </p>
            <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
              This may take a moment…
            </p>
          </div>
        )}

        {/* ── REPORT ───────────────────────────────── */}
        {phase === 'report' && result && (
          <>
            <div className="modal-header">
              <span style={{ fontWeight: 600, fontSize: '1rem', color: 'var(--text-primary)' }}>
                Extraction complete
              </span>
              <button className="modal-close" onClick={onClose}>✕</button>
            </div>
            <div className="modal-body" style={{ padding: '1.5rem' }}>
              <div className="report-card">
                <div>
                  <span className="badge badge-green">{result.framework}</span>
                </div>
                <p className="report-count">{result.apis.length}</p>
                <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem', marginTop: '-0.5rem', marginBottom: '0.75rem' }}>
                  endpoint{result.apis.length !== 1 ? 's' : ''} found
                </p>

                {result.groups && result.groups.length > 0 && (
                  <ul className="report-groups">
                    {result.groups.slice(0, 8).map(g => <li key={g}>{g}</li>)}
                    {result.groups.length > 8 && <li>+{result.groups.length - 8} more</li>}
                  </ul>
                )}

                {!result.supported && (
                  <p className="report-warn">Framework not fully supported — some endpoints may be missing.</p>
                )}
                {!result.llmEnrichmentEnabled && (
                  <p className="report-note">AI descriptions weren't generated — AI enrichment is disabled.</p>
                )}
                {!result.testRequestAvailable && (
                  <p className="report-note">No host URL set — Test Request won't be available.</p>
                )}
                {result.importedFromSpec && (
                  <p className="report-note">Imported directly from a committed OpenAPI file — static parsing and AI enrichment were skipped.</p>
                )}

                <div className="report-actions">
                  <button className="btn btn-outline" onClick={onClose}>Discard</button>
                  <button
                    className="btn btn-primary btn-lg"
                    onClick={handleReview}
                    disabled={result.apis.length === 0}
                  >
                    Review {result.apis.length} endpoint{result.apis.length !== 1 ? 's' : ''} →
                  </button>
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
