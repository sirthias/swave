/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.text

import scala.concurrent.duration._
import scodec.bits.ByteVector
import swave.compat.scodec._
import swave.core._
import swave.core.util._

class TextTransformationSpec extends SwaveSpec {

  implicit val env = StreamEnv()
  import env.defaultDispatcher

  val largeText =
    """Es war einmal, zur Zeit t=t0, ein armer rechtschaffener Vierpol namens Eddy Wirbelstrom. Er bewohnte einen
      |bescheiden möbilierten Hohlraum im Dielektrikum mit fließend kalten und warmen Sättigungsstrom. Leider mußte er
      |während der kalten Jahreszeit für die Erwärmung der Sperrschicht noch extra bezahlen. Seinen Lebensunterhalt
      |bestritt er mit einer Verstärkerzucht auf Transistorbasis.
      |
      |Eddy Wirbelstrom liebte mit der ganzen Kraft seiner Übertragungsfunktion - Ionchen!
      |
      |Die induktivste Spule mit dem kleinsten Verlustwinkel im ganzen Kreise und Tochter der einflußreichen EMKs.
      |Ihr remanenter Ferritkörper, ihre symmetrischen Netzintegrale, ihre überaus harmonischen Oberwellen - besonders
      |der Sinus - beeindruckten selbst die Suszeptibilität ausgedienter Leidener Flaschen, was viel heißen will.
      |Die jungfräulichen Kurven Ionchens waren auch wirklich sehr steil.
      |
      |Ionchens Vater, Cosinus Phi, ein bekannter industrieller Leistungsfaktor, hatte allerdings bereits konkrete
      |Schaltpläne für die Zukunft t >> t0 seiner Tochter. Sie sollte nur einer anerkannten Kapazität mit
      |ausgeprägtem Nennwert angeschlossen werden, aber wie so oft während der Lebensdauer L hatte auch diese Masche
      |einen Knoten, denn der Zufallstrieb wollte es anders.
      |
      |Als Ionchen eines Tages, zur Zeit t=t1, auf ihrem Picofarad vom Frisiersalon nach Hause fuhr (sie hatte sich eine
      |neue Stehwelle legen lassen), da geriet ihr ein Sägezahn in die Siebkette. Aber Eddy Wirbelstrom, der die Gegend
      |periodisch frequentierte, eilte mit minimaler Laufzeit hinzu, und es gelang ihm, Ionchens Kippschwingung noch
      |vor dem Maximum der Amplitude abzufangen, gleichzurichten und so die Resonanzkatastrophe zu verhindern.
      |
      |Es ist sicherlich nicht dem Zufall z1 zuzuschreiben, daß sie sich schon zur Zeit t = t1 + dt wiedersahen.
      |Eddy lud Ionchen zum Abendessen ins "Goldene Integral" ein. Aber das Integral war wie immer geschlossen.
      |"Macht nichts", sagte Ionchen, "ich habe zu Mittag gut gegessen und die Sättigungsinduktion hat bis jetzt
      |angehalten. Außerdem muß ich auf meine Feldlinie achten." Unter irgendeinem Vorwand lud Eddy sie dann zu einer
      |Rundfahrt im Rotor ein. Aber Ionchen lehnte ab: "Mir wird bei der zweiten Ableitung immer so leicht übel."
      |So unternahmen sie, ganz entgegen den Schaltplänen von Vater Cosinus Phi, einen kleinen Frequenzgang entlang dem
      |nahegelegenen Streufluß.
      |
      |Der Abend senkte sich über die komplexe Ebene und im imaginären Raum erglänzten die Sternschaltungen.
      |Eddy und Ionchen genossen die Isolierung vom lauten Getriebe der Welt und ließen ihre Blicke gegen 0 laufen.
      |Ein einsamer Modulationsbrummer flog vorbei, sanft plätscherten die elektromagnetischen Wellen und leise sang
      |eine Entstördrossel.
      |
      |Als sie an der Wheatston-Brücke angelangt waren, dort, wo der Blindstrom in den Streufluß mündet, lehnten sie
      |sich ans Gitter. Da nahm Eddy Wirbelstrom seinen ganzen Durchgriff zusammen und emittierte: "Bei Gauß!",
      |worauf Ionchen hauchte: "Deine lose Rückkopplung hat es mir angetan." Ihr Kilohertz schlug heftig.
      |
      |Der Informationsgehalt dieser Nachricht durchflutete Eddy. Die Summe über alle Theta, von Theta = 0 bis zu diesem
      |Ereignis war zu konvergent und beide entglitten der Kontrolle ihrer Zeitkonstanten.
      |Im Überschwange des jungen Glücks erreichten sie vollausgesteuert die Endstufen.
      |Und wenn sie nicht gedämpft wurden, so schwingen sie heute noch.""".stripMargin.replace("\r\n", "\n")

  "TextTransformations" - {

    "utf8decode" in {
      for (_ <- 1 to 10) {
        val random = XorShiftRandom()
        Spout(largeText.getBytes(UTF8).iterator)
          .injectSequential()
          .flatMap(_.take(random.nextLong(32)).drainToVector(32).map(ByteVector(_)))
          .utf8Decode
          .async()
          .drainToMkString(1000)
          .await(3.seconds) shouldEqual largeText
      }
    }

    "utf8encode" in {
      for (_ <- 1 to 10) {
        val random = XorShiftRandom()
        Spout(largeText.iterator)
          .injectSequential()
          .flatMap(_.take(random.nextLong(32)).drainToMkString(32))
          .utf8Encode
          .async()
          .drainFolding(ByteVector.empty)(_ ++ _)
          .await() shouldEqual ByteVector(largeText getBytes UTF8)
      }
    }

    "lines" in {
      for (_ <- 1 to 1) {
        val random = XorShiftRandom()
        Spout(largeText.iterator)
          .injectSequential()
          .flatMap(_.take(random.nextLong(32)).drainToMkString(32))
          .lines
          .async()
          .drainToVector(1000)
          .await() shouldEqual largeText.split('\n')
      }
    }
  }
}
