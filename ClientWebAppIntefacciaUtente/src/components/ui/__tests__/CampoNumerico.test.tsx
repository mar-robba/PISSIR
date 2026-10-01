import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import CampoNumerico from '../CampoNumerico'

// Il campo usato come nei form: il numero lo tiene il componente padre.
function Form({ iniziale }: { iniziale: number }) {
  const [minuti, setMinuti] = useState(iniziale)
  return (
    <>
      <CampoNumerico aria-label="minuti" valore={minuti} onCambia={setMinuti} />
      <span data-testid="salvato">{minuti}</span>
    </>
  )
}

describe('CampoNumerico', () => {
  it('lo 0 di partenza non resta davanti alle cifre scritte', async () => {
    const user = userEvent.setup()
    render(<Form iniziale={0} />)

    const campo = screen.getByLabelText('minuti')
    expect(campo).toHaveValue(null)

    await user.type(campo, '8899')
    expect(campo).toHaveDisplayValue('8899')
    expect(screen.getByTestId('salvato')).toHaveTextContent('8899')
  })

  it('svuotato il campo resta vuoto, senza tornare a 0', async () => {
    const user = userEvent.setup()
    render(<Form iniziale={15} />)

    const campo = screen.getByLabelText('minuti')
    expect(campo).toHaveDisplayValue('15')

    await user.clear(campo)
    expect(campo).toHaveDisplayValue('')

    await user.type(campo, '42')
    expect(campo).toHaveDisplayValue('42')
    expect(screen.getByTestId('salvato')).toHaveTextContent('42')
  })

  it('segue il numero quando cambia da fuori', () => {
    const { rerender } = render(<CampoNumerico aria-label="lat" valore={45.5} onCambia={() => {}} />)
    expect(screen.getByLabelText('lat')).toHaveDisplayValue('45.5')

    rerender(<CampoNumerico aria-label="lat" valore={9.2} onCambia={() => {}} />)
    expect(screen.getByLabelText('lat')).toHaveDisplayValue('9.2')
  })
})
