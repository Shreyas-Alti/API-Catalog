; ── include_router calls: app.include_router(router, prefix="/api") ──
(expression_statement
  (call
    function: (attribute
      attribute: (identifier) @method_name
    )
    arguments: (argument_list) @include_args
  )
  (#eq? @method_name "include_router")
) @include_call
