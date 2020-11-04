// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package prf_test

import (
	"testing"

	"github.com/golang/protobuf/proto"
	"github.com/google/tink/go/keyset"
	"github.com/google/tink/go/prf"
	"github.com/google/tink/go/testutil"
	tinkpb "github.com/google/tink/go/proto/tink_go_proto"
)

func TestKeyTemplates(t *testing.T) {
	var testCases = []struct {
		name     string
		template *tinkpb.KeyTemplate
	}{
		{name: "HMAC_PRF_SHA256",
			template: prf.HMACSHA256PRFKeyTemplate()},
		{name: "HMAC_PRF_SHA512",
			template: prf.HMACSHA512PRFKeyTemplate()},
		{name: "HKDF_PRF_SHA256",
			template: prf.HKDFSHA256PRFKeyTemplate()},
		{name: "AES_CMAC_PRF",
			template: prf.AESCMACPRFKeyTemplate()},
	}
	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			want, err := testutil.KeyTemplateProto(t, "prf", tc.name)
			if err != nil {
				t.Fatalf("testutil.KeyTemplateProto('prf', tc.name) failed: %s", err)
			}
			if !proto.Equal(want, tc.template) {
				t.Errorf("template %s is not equal to '%s'", tc.name, tc.template)
			}

			handle, err := keyset.NewHandle(tc.template)
			if err != nil {
				t.Errorf("keyset.NewHandle(tc.template) failed: %s", err)
			}
			prfset, err := prf.NewPRFSet(handle)
			if err != nil {
				t.Errorf("prf.NewPRFSet(handle) failed: %s", err)
			}

			msg := []byte("This is an ID needs to be redacted")
			output, err := prfset.ComputePrimaryPRF(msg, 16)
			if err != nil {
				t.Errorf("prfset.ComputePrimaryPRF(msg, 16) failed: %s", err)
			}
			if len(output) != 16 {
				t.Errorf("len(output) = %d, want 16", len(output))
			}
		})
	}
}
