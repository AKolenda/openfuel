# SPDX-License-Identifier: AGPL-3.0-only
from PIL import Image
import pytest
from tools.visual_gate import compare

def test_identical_pixels_pass(tmp_path):
    a=tmp_path/'a.png';b=tmp_path/'b.png';Image.new('RGB',(100,100),'white').save(a);Image.new('RGB',(100,100),'white').save(b)
    assert compare(a,b)[0]['passed']

def test_layout_change_does_not_silently_pass(tmp_path):
    a=tmp_path/'a.png';b=tmp_path/'b.png';Image.new('RGB',(100,100),'white').save(a);Image.new('RGB',(100,100),'black').save(b)
    result,_=compare(a,b);assert not result['passed'] and result['mismatching_fraction']==1

def test_dimension_mismatch_requires_review(tmp_path):
    a=tmp_path/'a.png';b=tmp_path/'b.png';Image.new('RGB',(100,100),'white').save(a);Image.new('RGB',(101,100),'white').save(b)
    with pytest.raises(ValueError):compare(a,b)
