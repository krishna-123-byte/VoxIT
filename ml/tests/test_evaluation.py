import importlib.util
from pathlib import Path

spec=importlib.util.spec_from_file_location("evaluate",Path(__file__).parents[1]/"evaluate.py")
evaluate=importlib.util.module_from_spec(spec); spec.loader.exec_module(evaluate)

def test_rejects_speaker_leakage():
    rows=[{"speaker_id":"s1","source_group":"a","split":"train"},{"speaker_id":"s1","source_group":"b","split":"test"}]
    try: evaluate.validate(rows); assert False
    except ValueError as error: assert "speaker_id leakage" in str(error)

def test_metrics_use_exact_threshold():
    rows=[{"label":"0","score":"0.49"},{"label":"1","score":"0.50"},{"label":"1","score":"0.9"},{"label":"0","score":"0.1"}]
    result=evaluate.metrics(rows,.5)
    assert result["accuracy"] == 1.0 and result["threshold"] == .5
