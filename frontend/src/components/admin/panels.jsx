import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import { CopyButton, ErrorBox, Loading } from '../ui'
import { formatBytes, formatDateTime } from '../../lib/format'

/* ==================================================================
   وضعیت سرویس
   ================================================================== */

export function StatusPanel() {
  const [state, setState] = useState({ loading: true })

  const load = useCallback(async () => {
    setState({ loading: true })
    try {
      setState({ loading: false, data: await api.admin.status() })
    } catch (e) {
      setState({ loading: false, error: e })
    }
  }, [])

  useEffect(() => { load() }, [load])

  if (state.loading) return <Loading rows={5} label="در حال خواندن وضعیت…" />
  if (state.error) return <ErrorBox message={state.error.message} onRetry={load} />

  const d = state.data
  const readOnly = d.readOnly || {}
  const mongo = d.mongo || {}
  const fields = d.searchFields?.fields || []
  const problems = d.searchFields?.problems || []
  const runtime = d.runtime || {}

  return (
    <div className="admin-panel">
      <div className="tile-row">
        <Tile
          label="حالت فقط-خواندنی"
          value={readOnly.clean ? 'سالم' : 'تخلف!'}
          tone={readOnly.clean ? 'ok' : 'error'}
          hint={`${(readOnly.blockedWriteAttempts ?? 0).toLocaleString('fa-IR')} تلاش نوشتن مسدود شد`}
        />
        <Tile
          label="اتصال پایگاه داده"
          value={mongo.reachable ? 'برقرار' : 'قطع'}
          tone={mongo.reachable ? 'ok' : 'error'}
          hint={mongo.reachable
            ? `${mongo.database}/${mongo.collection}`
            : String(mongo.error || '')}
        />
        <Tile
          label="پوشاندن دادهٔ حساس"
          value={{
            secretsOnly: 'فقط راز‌ها', partial: 'ماسک جزئی', off: 'خاموش',
          }[d.maskingProfile] || d.maskingProfile}
          tone={d.maskingProfile === 'off' ? 'error' : 'ok'}
        />
        <Tile
          label="حافظهٔ مصرفی"
          value={`${(runtime.heapUsedMb ?? 0).toLocaleString('fa-IR')} مگابایت`}
          hint={`از ${(runtime.heapMaxMb ?? 0).toLocaleString('fa-IR')} مگابایت · Java ${runtime.javaVersion || '?'}`}
        />
      </div>

      {!readOnly.clean && (
        <div className="warn-box error" role="alert">
          <span className="warn-icon" aria-hidden="true">⚠</span>
          <div>
            <strong>تلاش برای نوشتن در MongoDB مسدود شده است.</strong>
            <p>این نباید هرگز اتفاق بیفتد. آخرین موارد:</p>
            <ul className="violation-list">
              {(readOnly.recentViolations || []).slice(0, 5).map((v, i) => (
                <li key={i} className="mono" dir="ltr">{v}</li>
              ))}
            </ul>
          </div>
        </div>
      )}

      <section className="editor-block">
        <header className="editor-head">
          <div>
            <h3>فیلدهای جستجوی عادی</h3>
            <p className="editor-hint">
              ادعای «ایندکس دارد» در config با پایگاه داده مقایسه شده است.
            </p>
          </div>
          <button type="button" className="btn btn-sm" onClick={load}>بازخوانی</button>
        </header>

        {problems.length > 0 && (
          <div className="warn-box" role="alert">
            <span className="warn-icon" aria-hidden="true">⚠</span>
            <div>
              <strong>{problems.length.toLocaleString('fa-IR')} فیلد فعال است ولی ایندکس ندارد.</strong>
              <p>
                جستجو روی این فیلدها کل مجموعه را اسکن می‌کند. یا ایندکس بسازید
                (<code dir="ltr">ops/indexes.js</code>) یا در بخش «جستجو» غیرفعالشان کنید.
              </p>
            </div>
          </div>
        )}

        <table className="admin-table">
          <thead>
            <tr>
              <th>فیلد</th><th>برچسب</th><th>ادعای config</th><th>در پایگاه داده</th><th>وضعیت</th>
            </tr>
          </thead>
          <tbody>
            {fields.map((f) => (
              <tr key={f.field}>
                <td className="mono" dir="ltr">{f.field}</td>
                <td>{f.label}</td>
                <td>{f.claimed ? 'ایندکس دارد' : '—'}</td>
                <td>{f.actual ? 'ایندکس هست' : 'ایندکس نیست'}</td>
                <td><StatusChip status={f.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>

        <details className="raw-details">
          <summary>ایندکس‌های موجود روی مجموعه</summary>
          <ul className="mono-list" dir="ltr">
            {(d.searchFields?.existingIndexes || []).map((ix, i) => <li key={i}>{ix}</li>)}
          </ul>
        </details>
      </section>

      <WarningsBlock title="هشدارهای config.json" items={d.labelWarnings} />
      <WarningsBlock title="هشدارهای config.yaml" items={d.configWarnings} />

      <p className="table-note">
        تاریخچهٔ تغییرات در <code dir="ltr">{d.auditFile}</code> نگهداری می‌شود.
      </p>
    </div>
  )
}

function StatusChip({ status }) {
  const map = {
    ok: { label: 'درست', tone: 'ok' },
    'missing-index': { label: 'ایندکس ندارد', tone: 'error' },
    'not-claimed': { label: 'غیرقابل استفاده', tone: 'muted' },
    disabled: { label: 'غیرفعال', tone: 'muted' },
  }
  const s = map[status] || { label: status, tone: 'muted' }
  return <span className={`state-chip ${s.tone}`}>{s.label}</span>
}

function WarningsBlock({ title, items }) {
  if (!items?.length) return null
  return (
    <section className="editor-block">
      <h3>{title}</h3>
      <ul className="warn-list">{items.map((w, i) => <li key={i}>{w}</li>)}</ul>
    </section>
  )
}

function Tile({ label, value, hint, tone }) {
  return (
    <div className={`admin-tile ${tone ? `tone-${tone}` : ''}`}>
      <span className="admin-tile-label">{label}</span>
      <span className="admin-tile-value">{value}</span>
      {hint && <span className="admin-tile-hint">{hint}</span>}
    </div>
  )
}

/* ==================================================================
   آمار استفاده + برچسب‌های ترجمه‌نشده
   ================================================================== */

/**
 * مهم‌ترین بخش این صفحه، فهرست «برچسب‌های ترجمه‌نشده» است.
 *
 * از هیچ اسکنی نمی‌آید: هر بار پشتیبان لاگی را باز می‌کند که سرویسی در آن
 * ترجمه ندارد، همان‌جا ثبت می‌شود. یعنی فهرست کارهای باقی‌مانده خودش را
 * از روی استفادهٔ واقعی می‌سازد، و «افزودن به config» یک کلیک است.
 */
export function UsagePanel({ onAddLabel }) {
  const [state, setState] = useState({ loading: true })
  const [auto, setAuto] = useState(false)

  const load = useCallback(async () => {
    try {
      setState({ loading: false, data: await api.admin.usage() })
    } catch (e) {
      setState({ loading: false, error: e })
    }
  }, [])

  useEffect(() => { load() }, [load])
  useEffect(() => {
    if (!auto) return undefined
    const id = setInterval(load, 10000)
    return () => clearInterval(id)
  }, [auto, load])

  if (state.loading) return <Loading rows={5} label="در حال خواندن آمار…" />
  if (state.error) return <ErrorBox message={state.error.message} onRetry={load} />

  const d = state.data
  const unknown = d.unknownLabels || []
  const kinds = { routingKey: 'میکروسرویس', commandType: 'نوع دستور', title: 'عنوان' }

  return (
    <div className="admin-panel">
      <div className="tile-row">
        <Tile label="مدت کارکرد" value={humanUptime(d.uptimeSeconds)}
              hint={`از ${formatDateTime(d.startedAt)}`} />
        <Tile label="کل درخواست‌ها" value={(d.totalCalls || 0).toLocaleString('fa-IR')}
              hint={`${(d.totalErrors || 0).toLocaleString('fa-IR')} خطا`}
              tone={d.totalErrors > 0 ? 'warn' : undefined} />
        <Tile label="برچسب‌های ترجمه‌نشده" value={unknown.length.toLocaleString('fa-IR')}
              tone={unknown.length ? 'warn' : 'ok'}
              hint={unknown.length ? 'قابل افزودن به config' : 'چیزی باقی نمانده'} />
        <Tile label="پرس‌وجو در هر نمایش"
              value={describeOps(d.mongoOperations)}
              hint="باید همیشه ۱ باشد" />
      </div>

      <section className="editor-block">
        <header className="editor-head">
          <div>
            <h3>برچسب‌هایی که هنوز ترجمه ندارند</h3>
            <p className="editor-hint">
              این فهرست از لاگ‌هایی ساخته شده که پشتیبان‌ها واقعاً باز کرده‌اند —
              نه از اسکن پایگاه داده. برای هر مورد، «افزودن» شما را با کلید
              آماده به ویرایشگر می‌برد.
            </p>
          </div>
          <div className="chip-row">
            <button type="button" className={`chip ${auto ? 'active' : ''}`}
                    onClick={() => setAuto((a) => !a)}>
              به‌روزرسانی خودکار
            </button>
            <button type="button" className="chip" onClick={load}>بازخوانی</button>
            {unknown.length > 0 && (
              <button type="button" className="chip danger"
                      onClick={async () => { await api.admin.clearUnknown(); load() }}>
                پاک‌کردن فهرست
              </button>
            )}
          </div>
        </header>

        {unknown.length === 0 ? (
          <p className="editor-empty">
            هر برچسبی که تا حالا دیده شده، ترجمه دارد.
          </p>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th>نوع</th><th>مقدار خام</th><th>دفعات</th><th>آخرین بار</th><th>نمونه</th><th />
              </tr>
            </thead>
            <tbody>
              {unknown.map((u) => (
                <tr key={`${u.kind}:${u.value}`}>
                  <td>{kinds[u.kind] || u.kind}</td>
                  <td className="mono" dir="ltr">
                    {u.value}
                    <CopyButton value={u.value} label="" />
                  </td>
                  <td>{u.count.toLocaleString('fa-IR')}</td>
                  <td className="cell-sub">{formatDateTime(u.lastSeen)}</td>
                  <td>
                    {u.sampleLog && (
                      <Link className="mono" dir="ltr" to={`/log/${encodeURIComponent(u.sampleLog)}`}>
                        {u.sampleLog.slice(0, 10)}…
                      </Link>
                    )}
                  </td>
                  <td>
                    <button type="button" className="btn btn-sm"
                            onClick={() => onAddLabel({ kind: u.kind, value: u.value })}>
                      افزودن
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {d.unknownOverflow > 0 && (
          <p className="table-note">
            {d.unknownOverflow.toLocaleString('fa-IR')} مورد دیگر به‌دلیل پر شدن سقف حافظه
            ثبت نشد. اول همین‌ها را ترجمه کنید و فهرست را پاک کنید.
          </p>
        )}
      </section>

      <section className="editor-block">
        <h3>مسیرهای API</h3>
        <table className="admin-table">
          <thead>
            <tr>
              <th>مسیر</th><th>تعداد</th><th>خطا</th><th>میانه</th><th>۹۵٪</th><th>بیشینه</th>
            </tr>
          </thead>
          <tbody>
            {(d.endpoints || []).map((e) => (
              <tr key={e.endpoint}>
                <td className="mono" dir="ltr">{e.endpoint}</td>
                <td>{e.calls.toLocaleString('fa-IR')}</td>
                <td className={e.errors ? 'cell-error' : ''}>
                  {e.errors.toLocaleString('fa-IR')}
                </td>
                <td>{e.p50Ms.toLocaleString('fa-IR')} م‌ث</td>
                <td>{e.p95Ms.toLocaleString('fa-IR')} م‌ث</td>
                <td>{e.maxMs.toLocaleString('fa-IR')} م‌ث</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="table-note">
          صدک‌ها از هیستوگرام سطلی می‌آیند و تقریبی‌اند — برای «کند شده یا نه» کافی‌اند،
          نه برای گزارش دقیق. آمار با هر ری‌استارت صفر می‌شود؛ برای روند بلندمدت
          متریک‌های actuator را به Prometheus بدهید.
        </p>
      </section>

      <section className="editor-block">
        <h3>آخرین نمایش‌ها</h3>
        <table className="admin-table">
          <thead>
            <tr><th>شناسه</th><th>فیلد</th><th>نتیجه</th><th>زمان</th><th>پرس‌وجو</th><th>وقت</th></tr>
          </thead>
          <tbody>
            {(d.recentLookups || []).map((l, i) => (
              <tr key={i}>
                <td className="mono" dir="ltr">
                  <Link to={`/log/${encodeURIComponent(l.id)}`}>{l.id}</Link>
                </td>
                <td className="mono" dir="ltr">{l.field}</td>
                <td>{l.found
                  ? <span className="state-chip ok">پیدا شد</span>
                  : <span className="state-chip muted">پیدا نشد</span>}</td>
                <td>{l.tookMs.toLocaleString('fa-IR')} م‌ث</td>
                <td className={l.mongoOps === 1 ? '' : 'cell-error'}>
                  {l.mongoOps.toLocaleString('fa-IR')}
                </td>
                <td className="cell-sub">{formatDateTime(l.at)}</td>
              </tr>
            ))}
            {(d.recentLookups || []).length === 0 && (
              <tr><td colSpan={6} className="editor-empty">هنوز لاگی باز نشده.</td></tr>
            )}
          </tbody>
        </table>
      </section>

      {(d.slowest || []).length > 0 && (
        <section className="editor-block">
          <h3>کندترین درخواست‌ها</h3>
          <ul className="mono-list">
            {d.slowest.map((s, i) => (
              <li key={i}>
                <span dir="ltr">{s.endpoint}</span>
                {' — '}{s.tookMs.toLocaleString('fa-IR')} میلی‌ثانیه
                {' · '}<span className="cell-sub">{formatDateTime(s.at)}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function describeOps(histogram) {
  const entries = Object.entries(histogram || {})
  if (entries.length === 0) return '—'
  const bad = entries.filter(([ops]) => Number(ops) !== 1)
    .reduce((sum, [, count]) => sum + count, 0)
  return bad === 0 ? 'همیشه ۱' : `${bad.toLocaleString('fa-IR')} مورد ≠ ۱`
}

function humanUptime(seconds) {
  const s = Number(seconds) || 0
  if (s < 60) return `${s.toLocaleString('fa-IR')} ثانیه`
  if (s < 3600) return `${Math.round(s / 60).toLocaleString('fa-IR')} دقیقه`
  if (s < 86400) return `${Math.round(s / 3600).toLocaleString('fa-IR')} ساعت`
  return `${Math.round(s / 86400).toLocaleString('fa-IR')} روز`
}

/* ==================================================================
   تاریخچهٔ تغییرات + نسخه‌ها
   ================================================================== */

export function HistoryPanel({ onRestored }) {
  const [state, setState] = useState({ loading: true })
  const [busy, setBusy] = useState(null)
  const [preview, setPreview] = useState(null)

  const load = useCallback(async () => {
    try {
      const [audit, versions] = await Promise.all([
        api.admin.audit(100),
        api.admin.versions(),
      ])
      setState({ loading: false, audit, versions })
    } catch (e) {
      setState({ loading: false, error: e })
    }
  }, [])

  useEffect(() => { load() }, [load])

  if (state.loading) return <Loading rows={5} label="در حال خواندن تاریخچه…" />
  if (state.error) return <ErrorBox message={state.error.message} onRetry={load} />

  async function restore(name) {
    if (!window.confirm(`بازگشت به نسخهٔ ${name}؟ نسخهٔ فعلی هم پشتیبان گرفته می‌شود.`)) return
    setBusy(name)
    try {
      await api.admin.restore(name)
      await load()
      onRestored?.()
    } catch (e) {
      window.alert(e.message)
    }
    setBusy(null)
  }

  return (
    <div className="admin-panel">
      <section className="editor-block">
        <header className="editor-head">
          <div>
            <h3>نسخه‌های پشتیبان</h3>
            <p className="editor-hint">
              پیش از هر ذخیره، نسخهٔ قبلی خودکار نگه داشته می‌شود. بازگشت هم
              خودش یک پشتیبان تازه می‌سازد، پس هیچ نسخه‌ای از دست نمی‌رود.
            </p>
          </div>
          <button type="button" className="btn btn-sm" onClick={load}>بازخوانی</button>
        </header>

        <table className="admin-table">
          <thead><tr><th>نسخه</th><th>زمان</th><th>حجم</th><th /></tr></thead>
          <tbody>
            {(state.versions?.versions || []).map((v) => (
              <tr key={v.name}>
                <td className="mono" dir="ltr">{v.name}</td>
                <td className="cell-sub">{formatDateTime(v.at)}</td>
                <td>{formatBytes(v.sizeBytes)}</td>
                <td>
                  <div className="chip-row">
                    <button type="button" className="chip"
                            onClick={async () => setPreview(await api.admin.version(v.name))}>
                      دیدن
                    </button>
                    <button type="button" className="chip" disabled={busy === v.name}
                            onClick={() => restore(v.name)}>
                      {busy === v.name ? '…' : 'بازگشت'}
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {(state.versions?.versions || []).length === 0 && (
              <tr><td colSpan={4} className="editor-empty">هنوز نسخه‌ای ذخیره نشده.</td></tr>
            )}
          </tbody>
        </table>

        {preview && (
          <details className="raw-details" open>
            <summary>
              {preview.name}
              <CopyButton value={preview.content} label="کپی" />
            </summary>
            <pre className="raw-pre" dir="ltr">{preview.content}</pre>
          </details>
        )}
      </section>

      <section className="editor-block">
        <h3>تاریخچهٔ تغییرات</h3>
        <table className="admin-table">
          <thead><tr><th>زمان</th><th>کار</th><th>کاربر</th><th>از</th><th>جزئیات</th></tr></thead>
          <tbody>
            {(state.audit?.entries || []).map((e, i) => (
              <tr key={i}>
                <td className="cell-sub">{formatDateTime(e.at)}</td>
                <td><code dir="ltr">{e.action}</code></td>
                <td>{e.actor}</td>
                <td className="mono" dir="ltr">{e.client}</td>
                <td className="cell-sub">{detailsOf(e)}</td>
              </tr>
            ))}
            {(state.audit?.entries || []).length === 0 && (
              <tr><td colSpan={5} className="editor-empty">هنوز تغییری ثبت نشده.</td></tr>
            )}
          </tbody>
        </table>
        <p className="table-note">
          فایل کامل: <code dir="ltr">{state.audit?.file}</code> — قالب JSONL، قابل خواندن با
          grep و jq و قابل ارسال به ELK.
        </p>
      </section>
    </div>
  )
}

function detailsOf(entry) {
  const skip = new Set(['at', 'action', 'actor', 'client'])
  return Object.entries(entry)
    .filter(([k]) => !skip.has(k))
    .map(([k, v]) => `${k}=${Array.isArray(v) ? v.join('، ') : v}`)
    .join(' · ')
}

/* ==================================================================
   config.yaml — فقط نمایش
   ================================================================== */

export function BaseConfigPanel() {
  const [state, setState] = useState({ loading: true })

  useEffect(() => {
    api.admin.baseConfig()
      .then((data) => setState({ loading: false, data }))
      .catch((e) => setState({ loading: false, error: e }))
  }, [])

  if (state.loading) return <Loading rows={4} />
  if (state.error) return <ErrorBox message={state.error.message} />

  return (
    <section className="editor-block">
      <header className="editor-head">
        <div>
          <h3>تنظیمات زیرساختی — فقط نمایش</h3>
          <p className="editor-hint">{state.data.reason}</p>
        </div>
        <CopyButton value={state.data.content} label="کپی" />
      </header>
      <p className="table-note">
        مسیر: <code dir="ltr">{state.data.path}</code> — رمز اتصال پیش از نمایش پنهان شده است.
      </p>
      <pre className="raw-pre" dir="ltr">{state.data.content}</pre>
    </section>
  )
}
