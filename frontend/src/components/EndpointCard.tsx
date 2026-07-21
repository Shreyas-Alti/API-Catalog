import { useState } from 'react'
import type { EndpointDetail } from '../api/client'
import { endpointLabel } from '../utils/humanReadable'

const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e', POST: '#2563eb', PUT: '#f59e0b', DELETE: '#ef4444', PATCH: '#8b5cf6',
}

interface Props {
  ep: EndpointDetail
  repoId?: number
  repoName?: string
  repoFramework?: string
  isLoadingDetails?: boolean
  isActionLoading?: boolean
  isOpen: boolean
  onToggle: () => void
  onViewRepo?: (repoId: number) => void
  onDeleteRepo?: (repoId: number) => void
  onRescanRepo?: (repoId: number) => void
}

export function EndpointCard({ ep, repoId, repoName, repoFramework, isLoadingDetails, isActionLoading, isOpen, onToggle, onViewRepo, onDeleteRepo, onRescanRepo }: Props) {
  const label = endpointLabel(ep)
  const nonBodyParams = ep.parameters?.filter(p => p.location !== 'BODY') ?? []

  return (
    <div
      className={`endpoint-card${isOpen ? ' endpoint-card--open' : ''}`}
      onClick={onToggle}
      title="Click to expand details"
    >
      {/* ── Method + path ────────────────────────── */}
      <div className="ec-header">
        <span className="method-badge" style={{ background: METHOD_COLORS[ep.method] ?? '#64748b' }}>
          {ep.method}
        </span>
        <span className="ec-path mono">{ep.path}</span>
      </div>

      {/* ── Human-readable title ─────────────────── */}
      {label && <div className="ec-title">{label}</div>}

      {/* ── Description ──────────────────────────── */}
      {/* Only show description as body text if it differs from the title (avoids duplication
          when endpointLabel() already used the description as the card title) */}
      {ep.description && ep.description.trim() !== label && (
        <p className="ec-desc">{ep.description}</p>
      )}

      {/* ── Request body ─────────────────────────── */}
      {ep.requestBodyType && (
        <div className="ec-body-section">
          <div className="ec-body-header">
            <span>📥</span>
            <span className="ec-body-label">Accepts</span>
            <span className="ec-type-name">{ep.requestBodyType}</span>
          </div>
          {ep.requestBodyFields && ep.requestBodyFields.length > 0 && (
            <ul className="ec-field-list">
              {ep.requestBodyFields.slice(0, 4).map((f, i) => (
                <li key={i} className="ec-field-row">
                  <span className="ec-field-name">{f.name}</span>
                  <span className="ec-field-type">{f.type ?? ''}</span>
                  {f.validations?.some(v => /NotNull|NotBlank|NotEmpty/.test(v)) && (
                    <span className="ec-field-req">required</span>
                  )}
                </li>
              ))}
              {ep.requestBodyFields.length > 4 && (
                <li className="ec-more">+{ep.requestBodyFields.length - 4} more fields</li>
              )}
            </ul>
          )}
        </div>
      )}

      {/* ── Response body ────────────────────────── */}
      {ep.responseBodyType && (
        <div className="ec-body-section">
          <div className="ec-body-header">
            <span>📤</span>
            <span className="ec-body-label">Returns</span>
            <span className="ec-type-name">{ep.responseBodyType}</span>
          </div>
          {ep.responseBodyFields && ep.responseBodyFields.length > 0 && (
            <ul className="ec-field-list">
              {ep.responseBodyFields.slice(0, 3).map((f, i) => (
                <li key={i} className="ec-field-row">
                  <span className="ec-field-name">{f.name}</span>
                  <span className="ec-field-type">{f.type ?? ''}</span>
                </li>
              ))}
              {ep.responseBodyFields.length > 3 && (
                <li className="ec-more">+{ep.responseBodyFields.length - 3} more fields</li>
              )}
            </ul>
          )}
        </div>
      )}

      {/* ── Parameter chips ───────────────────────── */}
      {nonBodyParams.length > 0 && (
        <div className="ec-params">
          {nonBodyParams.map((p, i) => (
            <span key={i} className="ec-param-chip">
              {p.name}
              <span className="ec-param-loc">{p.location}</span>
              {p.required && <span className="ec-param-req">✱</span>}
            </span>
          ))}
        </div>
      )}

      {/* ── Footer ───────────────────────────────── */}
      <div className="ec-footer" onClick={e => e.stopPropagation()}>
        <div className="ec-footer-left">
          {/* Repo badge with actions — shown in cross-repo catalog view */}
          {repoName && (
            <span className="ec-repo-badge">
              <span className="ec-repo-name-link"
                onClick={() => repoId && onViewRepo?.(repoId)}
                title="Open repository page"
                style={{ cursor: onViewRepo ? 'pointer' : 'default' }}>
                📦 {repoName}
              </span>
              {repoFramework && (
                <span className="badge badge-green" style={{ fontSize: '0.68rem' }}>{repoFramework}</span>
              )}
              {onRescanRepo && repoId && (
                <button className="rp-action" disabled={isActionLoading}
                  onClick={() => onRescanRepo(repoId)} title="Re-scan">
                  {isActionLoading ? '…' : '↺'}
                </button>
              )}
              {onDeleteRepo && repoId && (
                <button className="rp-action rp-delete" disabled={isActionLoading}
                  onClick={() => {
                    if (window.confirm(`Delete repository "${repoName}" and all its endpoints?`))
                      onDeleteRepo(repoId)
                  }} title="Delete repository">
                  🗑
                </button>
              )}
            </span>
          )}
          {ep.statusCodes?.map(c => (
            <span key={c} className={`status-chip status-${Math.floor(c / 100)}xx`}>{c}</span>
          ))}
          {ep.tags?.map(t => <span key={t} className="tag-chip">{t}</span>)}
        </div>
        <div className="ec-footer-right">
          <span className="ec-toggle-hint" onClick={e => { e.stopPropagation(); onToggle(); }}>
            {isLoadingDetails ? '…' : '▶ Details'}
          </span>
        </div>
      </div>
    </div>
  )
}
