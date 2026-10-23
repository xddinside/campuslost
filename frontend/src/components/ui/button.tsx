import { Button as ButtonPrimitive } from "@base-ui/react/button"
import { cva, type VariantProps } from "class-variance-authority"
import { isValidElement } from "react"

import { cn } from "#/lib/utils.ts"

const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center rounded-full border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-[background-color,border-color,color,transform,box-shadow,opacity] duration-150 outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/40 active:scale-[0.97] disabled:pointer-events-none disabled:opacity-50 disabled:active:scale-100 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground shadow-sm hover:bg-[color-mix(in_oklch,var(--primary),black_8%)] hover:shadow active:bg-[color-mix(in_oklch,var(--primary),black_16%)] dark:hover:bg-[color-mix(in_oklch,var(--primary),white_10%)]",
        outline:
          "border-border bg-card text-foreground hover:bg-fill aria-expanded:bg-fill aria-expanded:text-foreground",
        secondary:
          "bg-fill-strong text-foreground hover:bg-[color-mix(in_oklch,var(--foreground),transparent_88%)] aria-expanded:bg-fill-strong aria-expanded:text-foreground",
        ghost:
          "text-foreground/80 hover:bg-fill hover:text-foreground aria-expanded:bg-fill aria-expanded:text-foreground",
        destructive:
          "bg-destructive/12 text-destructive-foreground hover:bg-destructive/20 focus-visible:ring-destructive/25 dark:bg-destructive/20 dark:hover:bg-destructive/30",
        link: "text-primary hover:underline underline-offset-4",
      },
      size: {
        default:
          "h-10 gap-1.5 px-5 has-data-[icon=inline-end]:pr-4 has-data-[icon=inline-start]:pl-4",
        xs: "h-7 gap-1 px-3 text-xs has-data-[icon=inline-end]:pr-2.5 has-data-[icon=inline-start]:pl-2.5 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-8 gap-1 px-3.5 text-[0.8125rem] has-data-[icon=inline-end]:pr-3 has-data-[icon=inline-start]:pl-3",
        lg: "h-11 gap-1.5 px-6 has-data-[icon=inline-end]:pr-5 has-data-[icon=inline-start]:pl-5",
        icon: "size-10",
        "icon-xs": "size-7 [&_svg:not([class*='size-'])]:size-3.5",
        "icon-sm": "size-8",
        "icon-lg": "size-11",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  nativeButton,
  render,
  ...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
  // When `render` produces a non-button element (e.g. a Link anchor), Base UI
  // requires nativeButton={false} or it warns about lost button semantics.
  const resolvedNativeButton =
    nativeButton ??
    (!isValidElement(render) ||
      render.type === "button" ||
      render.type === ButtonPrimitive)

  return (
    <ButtonPrimitive
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      nativeButton={resolvedNativeButton}
      render={render}
      {...props}
    />
  )
}

export { Button, buttonVariants }
