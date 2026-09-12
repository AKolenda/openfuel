PYTHON ?= python3
.DEFAULT_GOAL := help
.PHONY: help doctor dev site api seed check check-all android-core android ios-core ios package contracts
help:
	@echo "dev | site | api | seed | check | check-all | android-core | android | ios-core | ios | package | doctor"
doctor:
	$(PYTHON) tools/project.py doctor
dev:
	$(PYTHON) tools/project.py serve
site:
	$(PYTHON) tools/project.py site
api:
	$(PYTHON) tools/project.py api
seed:
	$(PYTHON) tools/project.py api-seed
check:
	$(PYTHON) tools/project.py check
check-all:
	$(PYTHON) tools/project.py check --web --native-cores
android-core:
	$(PYTHON) tools/project.py android-core
android:
	$(PYTHON) tools/project.py android-build
ios-core:
	$(PYTHON) tools/project.py ios-core
ios:
	$(PYTHON) tools/project.py ios-build
package:
	$(PYTHON) tools/project.py package
contracts:
	$(PYTHON) tools/project.py contracts --check
