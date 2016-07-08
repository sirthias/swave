/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.compat

import swave.compat.scodec.impl.ByteVectorBytes

package object scodec {

  implicit val byteVectorBytes: ByteVectorBytes = new ByteVectorBytes

}
