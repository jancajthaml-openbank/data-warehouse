#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
from helpers.unit import UnitHelper
from helpers.logger import logger


def before_feature(context, feature):
  context.log.info('')
  context.log.info('  (FEATURE) {}'.format(feature.name))


def before_scenario(context, scenario):
  context.log.info('')
  context.log.info('  (SCENARIO) {}'.format(scenario.name))
  context.log.info('')


def after_scenario(context, scenario):
  context.unit.collect_logs()


def before_all(context):
  context.log = logger()
  context.unit = UnitHelper(context)
  context.unit.configure()
  context.unit.download()


def after_all(context):
  context.unit.teardown()
