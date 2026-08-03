; ── APIRouter instantiation with prefix/tags ──
; APIRouter(prefix="/articles", tags=["articles"])
(assignment
  left: (identifier) @var_name
  right: (call
    function: (identifier) @func_name
    arguments: (argument_list) @router_args
  )
  (#eq? @func_name "APIRouter")
) @router_assignment
