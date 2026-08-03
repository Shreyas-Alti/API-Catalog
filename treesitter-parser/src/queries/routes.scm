; ── Route detection: @router.method("/path", ...) above a function def ──
; Captures: @router_var, @http_method, @path_arg (positional first string), @handler_name, @params
(decorated_definition
  (decorator
    (call
      function: (attribute
        object: (identifier) @router_var
        attribute: (identifier) @http_method
      )
      arguments: (argument_list) @args
    )
  )
  definition: (function_definition
    name: (identifier) @handler_name
    parameters: (parameters) @params
  ) @handler
) @route

(#match? @http_method "^(get|post|put|delete|patch|options|head)$")
