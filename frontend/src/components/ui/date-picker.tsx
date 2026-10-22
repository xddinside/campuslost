import { CalendarBlankIcon } from '@phosphor-icons/react'
import { format } from 'date-fns'
import { useState } from 'react'

import { Button } from '#/components/ui/button.tsx'
import { Calendar } from '#/components/ui/calendar.tsx'
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '#/components/ui/popover.tsx'
import { cn } from '#/lib/utils.ts'

type DatePickerProps = {
  id?: string
  value: string
  onValueChange: (value: string) => void
  max?: string
  placeholder?: string
  required?: boolean
  disabled?: boolean
  className?: string
}

function parseDate(value?: string) {
  return value ? new Date(`${value}T12:00:00`) : undefined
}

function DatePicker({
  id,
  value,
  onValueChange,
  max,
  placeholder = 'Choose a date',
  required,
  disabled,
  className,
}: DatePickerProps) {
  const [open, setOpen] = useState(false)
  const selectedDate = parseDate(value)
  const maxDate = parseDate(max)

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        render={
          <Button
            id={id}
            type="button"
            variant="outline"
            aria-required={required}
            disabled={disabled}
            className={cn(
              'h-11 w-full justify-between rounded-lg border-[var(--border)] bg-[var(--card)] px-3 text-left text-sm font-normal dark:bg-[var(--card)]',
              !selectedDate && 'text-[var(--muted-foreground)]',
              className,
            )}
          >
            <span>{selectedDate ? format(selectedDate, 'MMM d, yyyy') : placeholder}</span>
            <CalendarBlankIcon className="size-4 text-[var(--muted-foreground)]" aria-hidden="true" />
          </Button>
        }
      />
      <PopoverContent className="w-auto rounded-xl p-0" align="start">
        <Calendar
          mode="single"
          selected={selectedDate}
          defaultMonth={selectedDate}
          disabled={maxDate ? { after: maxDate } : undefined}
          onSelect={(nextDate) => {
            if (!nextDate) return
            onValueChange(format(nextDate, 'yyyy-MM-dd'))
            setOpen(false)
          }}
        />
      </PopoverContent>
    </Popover>
  )
}

export { DatePicker }
export type { DatePickerProps }
