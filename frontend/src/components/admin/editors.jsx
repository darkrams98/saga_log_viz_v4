import { useMemo, useState } from 'react'

/**
 * ویرایشگرهای پایه‌ای که بقیهٔ صفحهٔ مدیریتی از آن‌ها ساخته می‌شود.
 *
 * قاعدهٔ مشترک همه: **مقدار را عوض نمی‌کنند، نسخهٔ تازه برمی‌گردانند.**
 * حالت واقعی یک شیء JSON واحد در AdminPage است و همهٔ ویرایش‌ها روی
 * همان اعمال می‌شود. نتیجه: نمای «JSON خام» همیشه دقیقاً همان چیزی است
 * که ذخیره می‌شود، بدون هیچ تفاوت پنهانی.
 */

/** نگاشت کلید→مقدار (routingKeys، commandTypes، statuses، titles، fieldLabels) */
export function MapEditor({ title, hint, value, onChange, keyLabel = 'کلید',
                            valueLabel = 'برچسب فارسی', keyPlaceholder,
                            validateKey, highlight }) {
  const [query, setQuery] = useState('')
  const [newKey, setNewKey] = useState('')
  const [newValue, setNewValue] = useState('')

  const entries = useMemo(() => Object.entries(value || {}), [value])
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return entries
    return entries.filter(([k, v]) =>
      k.toLowerCase().includes(q) || String(v).toLowerCase().includes(q))
  }, [entries, query])

  function setEntry(key, next) {
    onChange({ ...(value || {}), [key]: next })
  }

  function renameEntry(oldKey, nextKey) {
    if (!nextKey || nextKey === oldKey) return
    // ترتیب کلیدها حفظ می‌شود تا فایل بعد از ویرایش به‌هم نریزد
    const out = {}
    for (const [k, v] of Object.entries(value || {})) {
      out[k === oldKey ? nextKey : k] = v
    }
    onChange(out)
  }

  function removeEntry(key) {
    const out = { ...(value || {}) }
    delete out[key]
    onChange(out)
  }

  function add(e) {
    e.preventDefault()
    const key = newKey.trim()
    if (!key) return
    if (validateKey) {
      const problem = validateKey(key)
      if (problem) return
    }
    onChange({ ...(value || {}), [key]: newValue.trim() || key })
    setNewKey('')
    setNewValue('')
  }

  const duplicate = newKey.trim() && Object.hasOwn(value || {}, newKey.trim())

  return (
    <section className="editor-block">
      <header className="editor-head">
        <div>
          <h3>{title}</h3>
          {hint && <p className="editor-hint">{hint}</p>}
        </div>
        <span className="editor-count">{entries.length.toLocaleString('fa-IR')} مورد</span>
      </header>

      <form className="map-add" onSubmit={add}>
        <input className="input mono" dir="ltr" value={newKey}
               placeholder={keyPlaceholder || keyLabel}
               onChange={(e) => setNewKey(e.target.value)} aria-label={`${keyLabel} جدید`} />
        <input className="input" value={newValue} placeholder={valueLabel}
               onChange={(e) => setNewValue(e.target.value)} aria-label={`${valueLabel} جدید`} />
        <button type="submit" className="btn btn-sm" disabled={!newKey.trim() || duplicate}>
          افزودن
        </button>
      </form>
      {duplicate && <p className="editor-error">این کلید از قبل وجود دارد.</p>}

      {entries.length > 6 && (
        <input className="input map-search" type="search" value={query}
               placeholder="جستجو در کلید یا برچسب…"
               onChange={(e) => setQuery(e.target.value)} aria-label="جستجو" />
      )}

      <div className="map-rows">
        {filtered.map(([key, val]) => (
          <div className={`map-row ${highlight === key ? 'is-new' : ''}`} key={key}>
            <input className="input mono" dir="ltr" defaultValue={key}
                   onBlur={(e) => renameEntry(key, e.target.value.trim())}
                   aria-label={keyLabel} />
            <input className="input" value={String(val ?? '')}
                   onChange={(e) => setEntry(key, e.target.value)}
                   aria-label={valueLabel} />
            <button type="button" className="chip danger" onClick={() => removeEntry(key)}
                    aria-label={`حذف ${key}`}>×</button>
          </div>
        ))}
        {filtered.length === 0 && (
          <p className="editor-empty">
            {entries.length ? 'موردی با این جستجو پیدا نشد.' : 'هنوز موردی اضافه نشده.'}
          </p>
        )}
      </div>
    </section>
  )
}

/**
 * الگوهای routingKey — با آزمایشگر زنده.
 *
 * نوشتن regex بدون امتحان‌کردن، منبع اصلی خطاست. کادر آزمایش اجازه می‌دهد
 * همان‌جا یک مقدار واقعی را بچسبانید و ببینید کدام الگو می‌گیردش — پیش از
 * اینکه ذخیره شود.
 */
export function PatternEditor({ value, onChange }) {
  const [probe, setProbe] = useState('')
  const list = value || []

  function update(index, patch) {
    onChange(list.map((p, i) => (i === index ? { ...p, ...patch } : p)))
  }

  function compiled(pattern) {
    try {
      return { regex: new RegExp(pattern), error: null }
    } catch (e) {
      return { regex: null, error: e.message }
    }
  }

  const firstMatch = useMemo(() => {
    if (!probe.trim()) return -1
    for (let i = 0; i < list.length; i++) {
      const { regex } = compiled(list[i].match || '')
      if (regex && regex.test(probe.trim())) return i
    }
    return -1
  }, [probe, list])

  return (
    <section className="editor-block">
      <header className="editor-head">
        <div>
          <h3>الگوهای میکروسرویس</h3>
          <p className="editor-hint">
            تور ایمنی برای نسخه‌های آینده. فقط وقتی بررسی می‌شوند که کلید دقیق پیدا نشود،
            و به ترتیب — اولین تطبیق برنده است.
          </p>
        </div>
        <button type="button" className="btn btn-sm"
                onClick={() => onChange([...list, { match: '', label: '' }])}>
          + الگو
        </button>
      </header>

      <div className="probe-box">
        <label className="editor-label" htmlFor="probe">آزمایش با یک مقدار واقعی</label>
        <input id="probe" className="input mono" dir="ltr" value={probe}
               placeholder="rabbitmq.yaghoot26.client.deposit.routing.key"
               onChange={(e) => setProbe(e.target.value)} />
        {probe.trim() && (
          <p className={firstMatch >= 0 ? 'probe-hit' : 'probe-miss'}>
            {firstMatch >= 0
              ? `الگوی ${(firstMatch + 1).toLocaleString('fa-IR')} می‌گیردش → «${list[firstMatch].label || '—'}»`
              : 'هیچ الگویی این مقدار را نمی‌گیرد؛ مقدار خام نمایش داده می‌شود.'}
          </p>
        )}
      </div>

      <div className="pattern-rows">
        {list.map((p, i) => {
          const { error } = compiled(p.match || '')
          const isHit = firstMatch === i
          return (
            <div className={`pattern-row ${isHit ? 'is-hit' : ''}`} key={i}>
              <span className="pattern-index">{(i + 1).toLocaleString('fa-IR')}</span>
              <div className="pattern-fields">
                <input className={`input mono ${error ? 'has-error' : ''}`} dir="ltr"
                       value={p.match || ''} placeholder="الگوی regex"
                       onChange={(e) => update(i, { match: e.target.value })}
                       aria-label={`الگوی ${i + 1}`} />
                <input className="input" value={p.label || ''} placeholder="نام فارسی سرویس"
                       onChange={(e) => update(i, { label: e.target.value })}
                       aria-label={`برچسب ${i + 1}`} />
              </div>
              <div className="pattern-actions">
                <button type="button" className="chip" disabled={i === 0}
                        onClick={() => {
                          const out = [...list]
                          ;[out[i - 1], out[i]] = [out[i], out[i - 1]]
                          onChange(out)
                        }} aria-label="بالاتر">↑</button>
                <button type="button" className="chip" disabled={i === list.length - 1}
                        onClick={() => {
                          const out = [...list]
                          ;[out[i], out[i + 1]] = [out[i + 1], out[i]]
                          onChange(out)
                        }} aria-label="پایین‌تر">↓</button>
                <button type="button" className="chip danger"
                        onClick={() => onChange(list.filter((_, x) => x !== i))}
                        aria-label="حذف">×</button>
              </div>
              {error && <p className="editor-error">الگوی نامعتبر: {error}</p>}
            </div>
          )
        })}
        {list.length === 0 && <p className="editor-empty">هنوز الگویی تعریف نشده.</p>}
      </div>
    </section>
  )
}

/** ردیف ساده برای مقدارهای تکی */
export function Field({ label, hint, children, error }) {
  return (
    <div className="admin-field">
      <label className="editor-label">{label}</label>
      {children}
      {hint && <p className="editor-hint">{hint}</p>}
      {error && <p className="editor-error">{error}</p>}
    </div>
  )
}

/** انتخاب یکی از چند گزینه، با توضیح هر گزینه */
export function RadioCards({ name, value, options, onChange }) {
  return (
    <div className="radio-cards">
      {options.map((o) => (
        <label key={o.value} className={`radio-card ${value === o.value ? 'selected' : ''}
                                         ${o.tone ? `tone-${o.tone}` : ''}`}>
          <input type="radio" name={name} value={o.value} checked={value === o.value}
                 onChange={() => onChange(o.value)} />
          <span className="radio-card-title">{o.label}</span>
          <span className="radio-card-text">{o.description}</span>
        </label>
      ))}
    </div>
  )
}
