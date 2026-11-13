import { Notice, Section } from './ui.jsx';

export function ValidationResult({ job, onCancel }) {
  const report = job.report;
  if (job.status !== 'running' && !report) return null;
  return <Section title="Validação da configuração">
    {job.status === 'running' && <>
      <p>{job.message}</p>
      <button className="button button-quiet" type="button" onClick={onCancel}>Cancelar análise</button>
    </>}
    {report && <>
      {report.playerImpactStale && <Notice tone="warning">{report.playerImpactMessage}</Notice>}
      {[['Erros', report.errors], ['Avisos', report.warnings], ['Compatibilidade', report.integrations]].map(([title, items]) =>
        items?.length > 0 && <div className="report-list" key={title}>
          <h3>{title}</h3><ul>{items.map((item, index) => <li key={index}>{item}</li>)}</ul>
        </div>
      )}
    </>}
  </Section>;
}
