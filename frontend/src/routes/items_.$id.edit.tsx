import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/items_/$id/edit')({
  component: RouteComponent,
})

function RouteComponent() {
  return <div>Hello "/items_/$id/edit"!</div>
}
