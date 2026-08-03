; ── Class definitions (Pydantic models etc.) ──
; Captures class name regardless of whether it has base classes
(class_definition
  name: (identifier) @class_name
  body: (block) @class_body
) @class_def
