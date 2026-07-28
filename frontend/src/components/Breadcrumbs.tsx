import { Link } from 'react-router-dom'

interface Props {
  repoName?: string | null
  step?: string
}

export default function Breadcrumbs({ repoName, step }: Props) {
  return (
    <div className="breadcrumbs">
      <Link to="/">Repositories</Link>
      {repoName && (
        <>
          <span>/</span>
          <span>{repoName}</span>
        </>
      )}
      {step && (
        <>
          <span>/</span>
          <span>{step}</span>
        </>
      )}
    </div>
  )
}
