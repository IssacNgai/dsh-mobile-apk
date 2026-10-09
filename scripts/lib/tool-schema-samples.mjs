// Generic input samples for the runtime output-schema gate; no tool-specific identities.
const SAMPLE = { string: 'sample', number: 1, integer: 1, boolean: false }

/** Sample one value, recursively filling only required children of an object. */
export const propSample = (prop) => {
  if (!prop || typeof prop !== 'object') return undefined
  if (Object.hasOwn(prop, 'const')) return prop.const
  if (Array.isArray(prop.enum) && prop.enum.length > 0) return prop.enum[0]
  if (Object.hasOwn(prop, 'default')) return prop.default
  if (prop.type === 'object') return argsFromSchema(prop)
  if (prop.type === 'array') return []
  return SAMPLE[prop.type]
}

/** Author required:true and compiled parent required:[keys] are distinct forms. */
export const argsFromSchema = (schema) => {
  const required = new Set(Array.isArray(schema?.required) ? schema.required : [])
  const out = {}
  for (const [key, prop] of Object.entries(schema?.properties ?? {})) {
    if (prop?.required === true || required.has(key)) out[key] = propSample(prop)
  }
  return out
}

/** Exercise optional objects explicitly; their required array describes children. */
export const optionalObjectVariants = (schema, base) => Object.entries(schema?.properties ?? {})
  .filter(([key, prop]) => prop?.type === 'object' && !Object.hasOwn(base, key))
  .map(([key, prop]) => ({ ...base, [key]: propSample(prop) }))
