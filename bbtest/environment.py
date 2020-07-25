import os
from helpers.unit import UnitHelper


def after_feature(context, feature):
  context.unit.collect_logs()


def before_all(context):
  context.unit = UnitHelper(context)
  context.unit.configure()
  context.unit.download()


def after_all(context):
  context.unit.teardown()
