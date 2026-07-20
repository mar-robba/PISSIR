import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import Card from '../Card'

describe('Card component', () => {
  it('renders title, action and children', () => {
    render(
      <Card title="My Title" action={<button>Act</button>}>
        <p>Body content</p>
      </Card>
    )

    expect(screen.getByText('My Title')).toBeInTheDocument()
    expect(screen.getByText('Act')).toBeInTheDocument()
    expect(screen.getByText('Body content')).toBeInTheDocument()
  })

  it('calls onClick when container is clicked', async () => {
    const user = userEvent.setup()
    const handle = vi.fn()

    render(
      <Card onClick={handle}>
        <span>Clickable</span>
      </Card>
    )

    await user.click(screen.getByText('Clickable'))
    expect(handle).toHaveBeenCalled()
  })
})
